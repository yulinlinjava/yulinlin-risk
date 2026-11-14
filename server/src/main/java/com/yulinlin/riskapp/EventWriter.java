package com.yulinlin.riskapp;

import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.riskapp.EventStore.Event;
import com.yulinlin.riskapp.EventStore.Insert;
import com.yulinlin.riskapp.EventStore.Result;
import com.yulinlin.riskapp.EventStore.Stored;
import com.yulinlin.riskapp.EventStore.SubjectKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class EventWriter {
    private static final Logger LOG = LoggerFactory.getLogger(EventWriter.class);
    private record Pending(String projectId, Event event, long acceptedAt, CompletableFuture<Result> reply) {}
    private record Outcome(Pending pending, Result result) {}
    private record Evaluation(Pending pending, Stored stored) {}

    private final EventStore store;
    private final Blacklists blacklists;
    private final OnlineRules rules;
    private final RouteSession orm;
    private final Clock clock;
    private final int batchSize;
    private final long batchWaitNanos;
    private final long onlineMaxDelay;
    private final ArrayBlockingQueue<Pending> queue;
    private volatile boolean running;
    private Thread writer;
    private long previousTime;

    EventWriter(EventStore store, Blacklists blacklists, OnlineRules rules, RouteSession orm,
                Clock clock, @Value("${risk.batch-size}") int batchSize, @Value("${risk.batch-wait-ms}") long batchWaitMs,
                @Value("${risk.queue-capacity}") int capacity, @Value("${risk.online-max-delay}") Duration onlineMaxDelay) {
        if (batchSize < 1 || batchSize > 500 || batchWaitMs < 0 || batchWaitMs > 100
                || capacity < batchSize || onlineMaxDelay.isNegative() || onlineMaxDelay.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("上报队列或批次参数无效");
        }
        this.store = store;
        this.blacklists = blacklists;
        this.rules = rules;
        this.orm = orm;
        this.clock = clock;
        this.batchSize = batchSize;
        this.batchWaitNanos = TimeUnit.MILLISECONDS.toNanos(batchWaitMs);
        this.onlineMaxDelay = onlineMaxDelay.toMillis();
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @PostConstruct void start() {
        running = true;
        writer = Thread.ofPlatform().daemon().name("risk-event-writer").start(this::writeLoop);
    }

    CompletableFuture<Result> submit(String projectId, Event event) {
        Inputs.text(event.eventId(), "eventId", 128);
        Inputs.text(event.userId(), "userId", 128);
        Inputs.behavior(event.behaviorType());
        Inputs.text(event.resourceId(), "resourceId", 256);
        long now = clock.millis();
        if (event.occurredAt() < now - Inputs.RETENTION || event.occurredAt() > now + 60_000) {
            throw Inputs.bad("occurredAt 应为最近 24 小时内的毫秒时间戳，最多允许提前 60 秒");
        }
        var reply = new CompletableFuture<Result>();
        synchronized (this) {
            if (!running || !queue.offer(new Pending(projectId, event, now, reply))) throw retryLater();
        }
        return reply;
    }

    @PreDestroy void stop() {
        synchronized (this) {
            running = false;
        }
        try {
            writer.join(Duration.ofSeconds(8));
            if (writer.isAlive()) writer.interrupt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLoop() {
        var batch = new ArrayList<Pending>(batchSize);
        try {
            while (running || !queue.isEmpty()) {
                Pending first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                collect(batch);
                write(batch);
                batch.clear();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                running = false;
                queue.drainTo(batch);
            }
            batch.forEach(p -> p.reply().completeExceptionally(retryLater()));
        }
    }

    private void collect(List<Pending> batch) throws InterruptedException {
        long deadline = System.nanoTime() + batchWaitNanos;
        while (batch.size() < batchSize) {
            queue.drainTo(batch, batchSize - batch.size());
            long remaining = deadline - System.nanoTime();
            if (batch.size() == batchSize || remaining <= 0 || !running) return;
            var next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) return;
            batch.add(next);
        }
    }

    private void write(List<Pending> batch) {
        var modified = new HashSet<SubjectKey>();
        List<Outcome> outcomes;
        try {
            outcomes = orm.transaction(() -> evaluate(batch, modified));
        } catch (RuntimeException e) {
            modified.forEach(rules::invalidate);
            LOG.error("行为批次提交失败，已作废受影响的内存窗口，事件数 {}", batch.size(), e);
            batch.forEach(p -> p.reply().completeExceptionally(retryLater()));
            return;
        } finally {
            rules.trim();
        }
        for (var outcome : outcomes) {
            if (outcome.result() != null) {
                outcome.pending().reply().complete(outcome.result());
            } else {
                outcome.pending().reply().completeExceptionally(new ResponseStatusException(
                        HttpStatus.CONFLICT, "eventId 已用于另一条行为"));
            }
        }
    }

    private List<Outcome> evaluate(List<Pending> batch, Set<SubjectKey> modified) {
        var ids = new HashMap<String, Set<String>>();
        var users = new HashMap<String, Set<String>>();
        for (var item : batch) {
            ids.computeIfAbsent(item.projectId(), key -> new LinkedHashSet<>()).add(item.event().eventId());
            users.computeIfAbsent(item.projectId(), key -> new LinkedHashSet<>()).add(item.event().userId());
        }
        var existing = new HashMap<String, Map<String, Stored>>();
        ids.forEach((project, eventIds) -> existing.put(project, store.existing(project, eventIds)));
        var restorable = findRestorable(batch, existing);

        var inserts = new ArrayList<Insert>();
        var evaluations = new ArrayList<Evaluation>(batch.size());
        // Trim the LRU after the transaction, when every same-batch event is visible in H2.
        for (var item : batch) {
            var seen = existing.get(item.projectId());
            var event = item.event();
            var saved = seen.get(event.eventId());
            if (saved == null) {
                saved = detect(item, modified, restorable.getOrDefault(item.projectId(), Set.of()).contains(event.userId()));
                seen.put(event.eventId(), saved);
                inserts.add(new Insert(item.projectId(), saved));
            }
            evaluations.add(new Evaluation(item, saved.event().equals(event) ? saved : null));
        }
        store.insert(inserts);
        var restricted = new HashMap<String, Set<String>>();
        users.forEach((project, userIds) -> restricted.put(project, blacklists.find(project, userIds)));
        return evaluations.stream().map(e -> new Outcome(e.pending(), e.stored() == null ? null : store.result(
                e.stored(), restricted.get(e.pending().projectId()).contains(e.pending().event().userId())))).toList();
    }

    private Map<String, Set<String>> findRestorable(List<Pending> batch, Map<String, Map<String, Stored>> existing) {
        long now = Math.max(previousTime, clock.millis());
        var cold = new HashMap<String, Set<String>>();
        for (var item : batch) {
            var event = item.event();
            if (historyOnly(item) || existing.get(item.projectId()).containsKey(event.eventId())) continue;
            if (rules.cold(new SubjectKey(item.projectId(), event.userId(), event.behaviorType()), now)) {
                cold.computeIfAbsent(item.projectId(), key -> new HashSet<>()).add(event.userId());
            }
        }
        long cutoff = rules.recoveryCutoff(now);
        var result = new HashMap<String, Set<String>>();
        cold.forEach((project, users) -> result.put(project, store.restorable(project, users, cutoff)));
        return result;
    }

    private Stored detect(Pending item, Set<SubjectKey> modified, boolean recoverable) {
        var event = item.event();
        long now = Math.max(previousTime, clock.millis());
        previousTime = now;
        if (historyOnly(item)) {
            return new Stored(event, now, EventStore.HISTORY_ONLY, List.of());
        }
        var key = new SubjectKey(item.projectId(), event.userId(), event.behaviorType());
        modified.add(key);
        var verdict = rules.check(key, event.resourceId(), now, recoverable);
        return new Stored(event, now, verdict.status(), verdict.matches());
    }

    private boolean historyOnly(Pending item) {
        return item.acceptedAt() - item.event().occurredAt() > onlineMaxDelay;
    }

    private static ResponseStatusException retryLater() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "上报暂不可用，请使用原 eventId 重试");
    }
}

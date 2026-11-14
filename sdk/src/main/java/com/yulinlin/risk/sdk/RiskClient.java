package com.yulinlin.risk.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** Bounded, best-effort asynchronous reporting. Never performs network I/O in tryReport. */
public final class RiskClient implements AutoCloseable {
    public record Metrics(int queued, long submitted, long delivered, long dropped, long sendFailures,
                          long blacklistRefreshFailures, boolean blacklistReady, long lastBlacklistRefreshAt) {}

    private final RiskTransport transport;
    private final ArrayBlockingQueue<RiskEvent> queue;
    private final int batchSize, retries, blacklistMaxUsers;
    private final long flushNanos, retryMillis, refreshMillis, shutdownMillis;
    private final LongAdder submitted = new LongAdder(), delivered = new LongAdder(), dropped = new LongAdder();
    private final LongAdder sendFailures = new LongAdder(), blacklistRefreshFailures = new LongAdder();
    private final AtomicReference<Set<String>> blacklist = new AtomicReference<>(Set.of());
    private final Object refreshLock = new Object();
    private long blacklistVersion = -1; // Guarded by refreshLock; committed only after a complete download.
    private volatile boolean running, closed, blacklistReady;
    private volatile long lastBlacklistRefreshAt, closingAtNanos = Long.MAX_VALUE;
    private Thread writer;
    private ScheduledExecutorService blacklistWorker;

    public RiskClient(RiskClientProperties config, RiskTransport transport) {
        config.validate();
        this.transport = java.util.Objects.requireNonNull(transport);
        queue = new ArrayBlockingQueue<>(config.getQueueCapacity());
        batchSize = config.getBatchSize();
        retries = config.getMaxRetries();
        blacklistMaxUsers = config.getBlacklistMaxUsers();
        flushNanos = config.getFlushInterval().toNanos();
        retryMillis = config.getRetryDelay().toMillis();
        refreshMillis = config.getBlacklistRefreshInterval().toMillis();
        shutdownMillis = config.getShutdownTimeout().toMillis();
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("RiskClient is closed");
        if (running) return;
        running = true;
        writer = new Thread(this::writeLoop, "risk-sdk-writer");
        writer.setDaemon(true);
        writer.start();
        blacklistWorker = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "risk-sdk-blacklist");
            thread.setDaemon(true);
            return thread;
        });
        blacklistWorker.scheduleWithFixedDelay(this::refreshBlacklist, 0, refreshMillis, TimeUnit.MILLISECONDS);
    }

    /** Reports an access with the supplied IP, occurrence time and URL preserved through batching and retry. */
    public boolean tryReport(String userId, String resourceId, String ip, long occurredAt, String url) {
        return tryReport(userId, "access", resourceId, ip, occurredAt, url);
    }

    /** Reports a named behavior. IP is required here; no DNS lookup or URL/time rewriting is performed. */
    public boolean tryReport(String userId, String action, String resourceId, String ip, long occurredAt, String url) {
        if (ip == null) {
            dropped.increment();
            return false;
        }
        return tryReport(new RiskEvent(UUID.randomUUID().toString(), userId, action, null, resourceId,
                url, "EVENT", occurredAt, 0, 200, ip));
    }

    /** Reports a behavior now; event ID and occurrence time are generated once on this call. */
    public boolean tryReport(String userId, String action, String resourceId) {
        return tryReport(new RiskEvent(userId, action, resourceId));
    }

    /** Reports a behavior that has no resource. */
    public boolean tryReport(String userId, String action) {
        return tryReport(userId, action, null);
    }

    /** False means not accepted locally; true is not a server acknowledgement. Use an explicit event for historical time. */
    public synchronized boolean tryReport(RiskEvent event) {
        if (!running || event == null || !event.valid() || !queue.offer(event)) {
            dropped.increment();
            return false;
        }
        submitted.increment();
        return true;
    }

    void captureFailed() { dropped.increment(); }

    public boolean isBlacklisted(String userId) {
        return userId != null && blacklist.get().contains(userId);
    }

    /** Checks the first page's version before downloading the rest; failure preserves the last complete snapshot. */
    public boolean refreshBlacklist() {
        synchronized (refreshLock) {
            if (closed) return false;
            try {
                var first = transport.blacklist(1, 500);
                if (first.total() < 0 || first.total() > blacklistMaxUsers || first.version() < 0) {
                    throw new IOException("Blacklist exceeds configured capacity or is invalid");
                }
                var next = new HashSet<String>();
                var page = first;
                int number = 1;
                int maxPages = (blacklistMaxUsers + 499) / 500;
                while (true) {
                    if (closed) return false;
                    if (page.page() != number || page.pageSize() != 500 || page.version() != first.version()
                            || page.total() != first.total() || page.userIds().size() > 500) {
                        throw new IOException("Blacklist changed during synchronization");
                    }
                    for (String id : page.userIds()) {
                        if (id == null || id.isBlank() || id.length() > 128 || !next.add(id)) {
                            throw new IOException("Invalid or duplicate blacklist entry");
                        }
                    }
                    if (number == 1 && first.version() == blacklistVersion) {
                        if (first.total() != blacklist.get().size()
                                || next.size() != Math.min(500L, first.total())) {
                            throw new IOException("Blacklist response conflicts with the cached version");
                        }
                        lastBlacklistRefreshAt = System.currentTimeMillis();
                        return true;
                    }
                    if (next.size() == first.total()) break;
                    if (next.size() > first.total() || page.userIds().size() != 500 || number >= maxPages) {
                        throw new IOException("Incomplete blacklist response");
                    }
                    page = transport.blacklist(++number, 500);
                }
                blacklist.set(Set.copyOf(next));
                blacklistVersion = first.version();
                lastBlacklistRefreshAt = System.currentTimeMillis();
                blacklistReady = true;
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                blacklistRefreshFailures.increment();
                return false;
            } catch (IOException | RuntimeException e) {
                blacklistRefreshFailures.increment();
                return false;
            }
        }
    }

    /** Explicit synchronous query. Reporting and blacklist checks do not call this automatically. */
    public JsonNode queryRisk(String userId) throws IOException, InterruptedException {
        return transport.risk(userId);
    }

    public Metrics metrics() {
        return new Metrics(queue.size(), submitted.sum(), delivered.sum(), dropped.sum(), sendFailures.sum(),
                blacklistRefreshFailures.sum(), blacklistReady, lastBlacklistRefreshAt);
    }

    private void writeLoop() {
        while ((running || !queue.isEmpty()) && System.nanoTime() < closingAtNanos) {
            var events = new ArrayList<RiskEvent>(batchSize);
            try {
                var first = queue.poll(Math.min(flushNanos, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS);
                if (first == null) continue;
                events.add(first);
                long until = System.nanoTime() + flushNanos;
                while (events.size() < batchSize) {
                    queue.drainTo(events, batchSize - events.size());
                    long remaining = Math.min(until, closingAtNanos) - System.nanoTime();
                    if (events.size() == batchSize || remaining <= 0 || !running) break;
                    var next = queue.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS);
                    if (next != null) events.add(next);
                }
                var batch = new RiskBatch(UUID.randomUUID().toString(), events);
                if (send(batch)) delivered.add(events.size());
                else dropped.add(events.size());
            } catch (InterruptedException e) {
                dropped.add(events.size());
                if (!running) break;
            } catch (RuntimeException e) {
                sendFailures.increment();
                dropped.add(events.size());
            }
        }
        var remainder = new ArrayList<RiskEvent>();
        queue.drainTo(remainder);
        dropped.add(remainder.size());
    }

    private boolean send(RiskBatch batch) throws InterruptedException {
        for (int attempt = 0; attempt <= retries && System.nanoTime() < closingAtNanos; attempt++) {
            try {
                var result = transport.send(batch);
                if (result.status() >= 200 && result.status() < 300 && result.accepted() == batch.events().size()) return true;
                sendFailures.increment();
                if (result.status() != 429 && result.status() < 500) return false;
            } catch (IOException e) {
                sendFailures.increment();
            }
            if (attempt < retries) {
                long delay = Math.min(30_000, retryMillis * (1L << attempt));
                long remaining = TimeUnit.NANOSECONDS.toMillis(closingAtNanos - System.nanoTime());
                if (!running && remaining <= 0) return false;
                Thread.sleep(running ? delay : Math.min(delay, remaining));
            }
        }
        return false;
    }

    @Override public void close() {
        Thread activeWriter;
        synchronized (this) {
            if (closed) return;
            closed = true;
            running = false;
            closingAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownMillis);
            if (blacklistWorker != null) blacklistWorker.shutdownNow();
            activeWriter = writer;
        }
        if (activeWriter == null) return;
        try {
            activeWriter.join(shutdownMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (activeWriter.isAlive()) activeWriter.interrupt();
        }
    }
}

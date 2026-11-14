package com.yulinlin.riskapp.global;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulinlin.data.core.node.CommandNode;
import com.yulinlin.data.core.parse.ParseType;
import com.yulinlin.data.core.request.ExecuteRequest;
import com.yulinlin.data.core.request.QueryRequest;
import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.data.core.wrapper.ISelectWrapper;
import com.yulinlin.data.core.wrapper.IDeleteWrapper;
import com.yulinlin.riskapp.schema.model.RiskTables;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Cross-endpoint analysis of the selected, server-reported business requests. */
@Service
public class GlobalRiskService {
    public record Event(String eventId, String userId, String action, String resourceType, String resourceId,
                        String route, String method, long occurredAt, long durationMs, int status,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String ip) {
        // Keep old payload digests unchanged when no IP was supplied, including retries after upgrade.
        public Event(String eventId, String userId, String action, String resourceType, String resourceId,
                     String route, String method, long occurredAt, long durationMs, int status) {
            this(eventId, userId, action, resourceType, resourceId, route, method, occurredAt, durationMs, status, null);
        }
    }
    public record Batch(String batchId, List<Event> events) {}
    public record Ack(int accepted, boolean duplicate) {}
    public record Reason(String ruleId, String reason, Map<String, Long> metrics) {}
    public record Risk(String status, List<Reason> reasons, Long judgedAt, boolean dataComplete) {}
    public record User(String userId, long count, long failures, int actionCount, boolean suspected) {}
    public record Page<T>(List<T> items, int page, boolean hasMore) {}
    public record Action(String action, long count) {}
    public record Trend(long at, long count, long failures) {}
    public record Evidence(long at, String ruleId, String reason) {}
    public record Report(String userId, long from, long to, long count, long failures, long denied,
                         List<Action> actions, List<Trend> trend, List<Evidence> evidence,
                         String coverage, boolean sampled, long historyOnlyCount, long sampleCount,
                         boolean samplesLimited, boolean actionCountsTruncated, boolean resourceCountsTruncated) {}
    public record HistoryEvent(String eventId, String userId, String action, String resourceType, String resourceId,
                               String route, String method, long occurredAt, long durationMs, int status,
                               String sampleKind, String ip) {}

    public static final long RETENTION = Duration.ofHours(24).toMillis();
    static final long SUMMARY_BUCKET = Duration.ofMinutes(5).toMillis();
    private static final String OTHER = "__OTHER__";
    private static final Logger LOG = LoggerFactory.getLogger(GlobalRiskService.class);
    private record Key(String projectId, String userId) {}
    private record BucketKey(String userId, long at) {}
    private record Pending(String projectId, Batch batch, String digest, CompletableFuture<Ack> reply) {}
    private record Committed(Ack ack, Map<Key, GlobalWindow> states) {}
    private record Sample(Event event, String kind) {}
    private record Range(long from, long to, long sampleFrom, long sampleTo) {}
    public static class UserCounts {
        public String userId;
        public long count;
        public long failures;
        public boolean suspected;
    }

    /** Metadata is bounded; counts include every accepted event even when samples are capped. */
    public static final class Summary {
        public LinkedHashMap<String, Long> actions = new LinkedHashMap<>();
        public LinkedHashSet<String> resources = new LinkedHashSet<>();
        public LinkedHashMap<String, Evidence> evidence = new LinkedHashMap<>();
        public int normalSamples;
        public int anomalySamples;
        public long otherActions;
        public long historyOnly;
        public boolean resourcesTruncated;
        public boolean samplesLimited;
        long count;
        long failures;
        long denied;
        boolean suspected;

        void add(Event event, GlobalRiskSettings settings, boolean online) {
            count++;
            if (event.status() >= 400) failures++;
            if (GlobalWindow.denied(event.status())) denied++;
            if (!online) historyOnly++;
            if (actions.containsKey(event.action()) || actions.size() < settings.getActionLimit()) {
                actions.merge(event.action(), 1L, Long::sum);
            } else {
                otherActions++;
            }
            if (event.resourceId() != null) {
                String resource = (event.resourceType() == null ? "" : event.resourceType()) + ':' + event.resourceId();
                if (resources.contains(resource) || resources.size() < 32) resources.add(resource);
                else resourcesTruncated = true;
            }
        }
    }

    private final RouteSession orm;
    private final ObjectMapper json;
    private final Clock clock;
    private final GlobalRiskSettings settings;
    private final GlobalEventLog eventLog;
    private final ArrayBlockingQueue<Pending> queue;
    private final Semaphore analyticsSlots;
    private final Object admission = new Object();
    // Owned by the one writer. Values are committed snapshots and are never mutated in place.
    private final LinkedHashMap<Key, GlobalWindow> cache = new LinkedHashMap<>(16, .75f, true);
    private volatile boolean running;
    private Thread writer;

    public GlobalRiskService(RouteSession orm, ObjectMapper json,
                             Clock clock, GlobalRiskSettings settings, GlobalEventLog eventLog) {
        settings.validate();
        this.orm = orm;
        this.json = json;
        this.clock = clock;
        this.settings = settings;
        this.eventLog = eventLog;
        this.queue = new ArrayBlockingQueue<>(settings.getQueueCapacity());
        this.analyticsSlots = new Semaphore(settings.getAnalyticsConcurrency());
    }

    @PostConstruct public void start() {
        running = true;
        writer = Thread.ofPlatform().daemon().name("global-risk-writer").start(this::writeLoop);
    }

    @PreDestroy public void stop() {
        synchronized (admission) {
            running = false;
            Pending item;
            while ((item = queue.poll()) != null) item.reply().completeExceptionally(unavailable());
        }
        if (writer != null) {
            // Interrupting H2's file I/O can close its channel. Let the current transaction finish.
            boolean interrupted = false;
            while (writer.isAlive()) {
                try { writer.join(1000); }
                catch (InterruptedException error) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public CompletableFuture<Ack> submit(String projectId, Batch input) {
        required(projectId, "projectId", 36);
        Batch batch = validateShape(input);
        var reply = new CompletableFuture<Ack>();
        var pending = new Pending(projectId, batch, digest(encode(batch)), reply);
        synchronized (admission) {
            if (!running || !queue.offer(pending)) reply.completeExceptionally(unavailable());
        }
        return reply;
    }

    public Risk risk(String projectId, String userId) {
        required(userId, "userId", 128);
        long now = clock.millis();
        ISelectWrapper<RiskTables.GlobalState, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.GlobalState());
        select.where().eq("projectId", projectId).eq("userId", userId).gte("updatedAt", now - RETENTION);
        var states = orm.select(QueryRequest.newInstance(RiskTables.GlobalState.class, select).cache(false).autoUpdate(false));
        return states.isEmpty() ? new Risk("NO_DATA", List.of(), null, true)
                : states.getFirst().state.evaluate(now, settings);
    }

    public Page<User> users(String projectId, long from, long to, String userId, long minCount, int page, int pageSize) {
        var range = range(from, to);
        if (userId != null) required(userId, "userId", 128);
        if (minCount < 0) throw bad("minCount 不能为负数");
        int offset = offset(page, pageSize, 50);
        return limited(() -> {
            var params = new HashMap<String, Object>(Map.of("project", projectId, "from", range.from(), "to", range.to(),
                    "minimum", userId == null ? minCount : 0L, "limit", pageSize + 1, "offset", offset));
            String byUser = userId == null ? "" : " AND user_id = #{user}";
            if (userId != null) params.put("user", userId);
            var counts = orm.select(QueryRequest.newInstance("""
                    SELECT user_id AS "userId", SUM(request_count) AS "count", SUM(failures) AS "failures",
                           MAX(CASE WHEN suspected THEN 1 ELSE 0 END) > 0 AS "suspected"
                    FROM risk_global_summary WHERE project_id = #{project} AND bucket_at >= #{from} AND bucket_at < #{to}
                    """ + byUser + " GROUP BY user_id HAVING SUM(request_count) >= #{minimum}"
                    + " ORDER BY SUM(request_count) DESC, user_id LIMIT #{limit} OFFSET #{offset}",
                    params, UserCounts.class).cache(false).autoUpdate(false));
            var visible = counts.subList(0, Math.min(counts.size(), pageSize));
            Map<String, Set<String>> actions = actionNames(projectId, visible, range);
            var items = visible.stream().map(row -> new User(row.userId, row.count, row.failures,
                    actions.getOrDefault(row.userId, Set.of()).size(), row.suspected)).toList();
            return new Page<>(items, page, counts.size() > pageSize);
        });
    }

    public Report report(String projectId, String userId, long from, long to) {
        required(userId, "userId", 128);
        var range = range(from, to);
        return limited(() -> {
            ISelectWrapper<RiskTables.GlobalSummary, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.GlobalSummary());
            select.where().eq("projectId", projectId).eq("userId", userId).gte("bucketAt", range.from()).lt("bucketAt", range.to());
            select.orderBy("bucketAt", true);
            var rows = orm.select(QueryRequest.newInstance(RiskTables.GlobalSummary.class, select).cache(false).autoUpdate(false));
            long count = 0, failures = 0, denied = 0;
            long historyOnly = 0, sampleCount = 0;
            boolean samplesLimited = false, actionsTruncated = false, resourcesTruncated = false;
            var actions = new TreeMap<String, Long>();
            var trend = new ArrayList<Trend>();
            var evidence = new ArrayList<Evidence>();
            for (var row : rows) {
                Summary summary = readSummary(row);
                count += summary.count; failures += summary.failures; denied += summary.denied;
                historyOnly += summary.historyOnly;
                sampleCount += summary.normalSamples + summary.anomalySamples;
                samplesLimited |= summary.samplesLimited;
                actionsTruncated |= summary.otherActions > 0;
                resourcesTruncated |= summary.resourcesTruncated;
                summary.actions.forEach((action, n) -> actions.merge(action, n, Long::sum));
                if (summary.otherActions > 0) actions.merge(OTHER, summary.otherActions, Long::sum);
                trend.add(new Trend(row.bucketAt, summary.count, summary.failures));
                evidence.addAll(summary.evidence.values());
            }
            evidence.sort(Comparator.comparingLong(Evidence::at).thenComparing(Evidence::ruleId));
            var distribution = actions.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey())).map(entry -> new Action(entry.getKey(), entry.getValue())).toList();
            return new Report(userId, range.from(), range.to(), count, failures, denied, distribution,
                    List.copyOf(trend), List.copyOf(evidence), "SELECTED_ENDPOINTS", true, historyOnly, sampleCount,
                    samplesLimited, actionsTruncated, resourcesTruncated);
        });
    }

    public Page<HistoryEvent> events(String projectId, String userId, long from, long to, int page, int pageSize) {
        required(userId, "userId", 128);
        var range = range(from, to);
        int offset = offset(page, pageSize, 200);
        return limited(() -> {
            var rows = orm.select(QueryRequest.newInstance("""
                    SELECT event_id AS "eventId", user_id AS "userId", action AS "action",
                           resource_type AS "resourceType", resource_id AS "resourceId", route AS "route",
                           method AS "method", occurred_at AS "occurredAt", duration_ms AS "durationMs",
                           response_status AS "responseStatus", sample_kind AS "sampleKind", ip AS "ip" FROM risk_global_sample
                    WHERE project_id = #{project} AND user_id = #{user} AND occurred_at >= #{from} AND occurred_at < #{to}
                    ORDER BY occurred_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}
                    """, Map.of("project", projectId, "user", userId, "from", Math.max(range.sampleFrom(), clock.millis() - GlobalEventLog.RETENTION), "to", range.sampleTo(),
                    "limit", pageSize + 1, "offset", offset), RiskTables.GlobalSample.class).cache(false).autoUpdate(false));
            var items = rows.subList(0, Math.min(rows.size(), pageSize)).stream().map(row -> new HistoryEvent(
                    row.eventId, row.userId, row.action, row.resourceType, row.resourceId, row.route, row.method,
                    row.occurredAt, row.durationMs, row.responseStatus, row.sampleKind, row.ip)).toList();
            return new Page<>(items, page, rows.size() > pageSize);
        });
    }

    public GlobalEventLog.Page eventLog(String projectId, String userId, Long from, Long to, String cursor, int pageSize) {
        return limited(() -> eventLog.page(projectId, userId, from, to, cursor, pageSize));
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void cleanExpired() {
        long cutoff = clock.millis() - RETENTION;
        // Independent bounded commits leave headroom for ingestion and Admin queries.
        for (String table : List.of("risk_global_batch", "risk_global_summary", "risk_global_state", "risk_global_sample", "risk_global_sample_budget")) {
            String column = switch (table) {
                case "risk_global_batch" -> "created_at";
                case "risk_global_summary" -> "bucket_at";
                case "risk_global_state" -> "updated_at";
                case "risk_global_sample" -> "occurred_at";
                default -> "minute_at";
            };
            long expiry = switch (table) {
                case "risk_global_summary" -> cutoff - SUMMARY_BUCKET + 1;
                case "risk_global_sample" -> clock.millis() - GlobalEventLog.RETENTION;
                default -> cutoff;
            };
            for (int i = 0; i < 4; i++) {
                if (orm.update(ExecuteRequest.newInstance("DELETE FROM " + table + " WHERE " + column
                        + " < #{expiry} LIMIT 1000", Map.of("expiry", expiry))) < 1000) break;
            }
        }
    }

    private void writeLoop() {
        while (running || !queue.isEmpty()) {
            Pending pending;
            try { pending = queue.poll(250, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { continue; }
            if (pending == null) continue;
            try {
                Committed committed = orm.transaction(() -> process(pending));
                if (committed == null) throw new IllegalStateException("未完成风控事务");
                committed.states().forEach(cache::put);
                while (cache.size() > settings.getMaxActiveUsers()) cache.pollFirstEntry();
                pending.reply().complete(committed.ack());
            } catch (ResponseStatusException error) {
                pending.reply().completeExceptionally(error);
            } catch (RuntimeException error) {
                // Only private copies were changed. Neither cache nor caller sees a partial commit.
                LOG.error("全局风控批次提交失败，事件数 {}", pending.batch().events().size(), error);
                pending.reply().completeExceptionally(unavailable());
            }
        }
    }

    private Committed process(Pending pending) {
        long now = clock.millis();
        long cutoff = now - RETENTION;
        ISelectWrapper<RiskTables.GlobalBatch, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.GlobalBatch());
        select.where().eq("projectId", pending.projectId()).eq("batchId", pending.batch().batchId()).gte("createdAt", cutoff);
        var previous = orm.select(QueryRequest.newInstance(RiskTables.GlobalBatch.class, select).cache(false).autoUpdate(false));
        if (!previous.isEmpty()) {
            if (!previous.getFirst().payloadDigest.equals(pending.digest())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "batchId 已用于不同内容");
            }
            return new Committed(new Ack(previous.getFirst().accepted, true), Map.of());
        }
        for (Event event : pending.batch().events()) {
            if (event.occurredAt() < cutoff || event.occurredAt() > now + 60_000) {
                throw bad("occurredAt 必须位于最近 24 小时内，未来时钟偏差最多 60 秒");
            }
        }
        var eventList = pending.batch().events().stream().sorted(Comparator.comparingLong(Event::occurredAt).thenComparing(Event::eventId)).toList();
        var keys = new LinkedHashSet<BucketKey>();
        var onlineUsers = new LinkedHashSet<String>();
        for (Event event : eventList) {
            long bucket = bucket(event.occurredAt());
            keys.add(new BucketKey(event.userId(), bucket));
            if (online(event, now)) onlineUsers.add(event.userId());
        }
        Map<BucketKey, Summary> summaries = loadSummaries(pending.projectId(), keys);
        Map<Key, GlobalWindow> states = loadStates(pending.projectId(), onlineUsers, cutoff);
        for (Event event : eventList) {
            var summary = summaries.get(new BucketKey(event.userId(), bucket(event.occurredAt())));
            boolean online = online(event, now);
            if (summary != null) summary.add(event, settings, online);
            if (online) states.get(new Key(pending.projectId(), event.userId())).add(event, now, settings.getResourceLimit());
        }
        var verdicts = new HashMap<String, Risk>();
        states.forEach((key, state) -> verdicts.put(key.userId(), state.evaluate(now, settings)));
        ISelectWrapper<RiskTables.GlobalSampleBudget, ?, ?, ?> budgetSelect = orm.getWrapperFactory().createSelectWrapper(new RiskTables.GlobalSampleBudget());
        budgetSelect.where().eq("minuteAt", GlobalWindow.minute(now));
        var budgets = orm.select(QueryRequest.newInstance(RiskTables.GlobalSampleBudget.class, budgetSelect).cache(false).autoUpdate(false));
        int sampleBudget = budgets.isEmpty() ? 0 : budgets.getFirst().sampleCount;
        var samples = new ArrayList<Sample>();
        for (Event event : eventList) {
            var summary = summaries.get(new BucketKey(event.userId(), bucket(event.occurredAt())));
            if (summary == null) continue;
            if (event.occurredAt() < now - GlobalEventLog.RETENTION) {
                summary.samplesLimited = true;
                continue;
            }
            Risk verdict = online(event, now) ? verdicts.get(event.userId()) : null;
            boolean suspected = verdict != null && verdict.status().equals("SUSPECTED");
            if (suspected) {
                summary.suspected = true;
                verdict.reasons().forEach(reason -> summary.evidence.putIfAbsent(reason.ruleId(), new Evidence(now, reason.ruleId(), reason.reason())));
            }
            boolean room = suspected ? summary.anomalySamples < settings.getAnomalySamples()
                    : summary.normalSamples < settings.getNormalSamples();
            if (room && sampleBudget < settings.getSamplesPerMinute()) {
                samples.add(new Sample(event, suspected ? "ANOMALY" : "NORMAL"));
                if (suspected) summary.anomalySamples++; else summary.normalSamples++;
                sampleBudget++;
            } else {
                summary.samplesLimited = true;
            }
        }
        saveSummaries(pending.projectId(), summaries);
        saveStates(states);
        eventLog.insert(pending.projectId(), eventList, now);
        saveSamples(pending.projectId(), samples);
        if (!samples.isEmpty()) orm.update(ExecuteRequest.newInstance(
                "MERGE INTO risk_global_sample_budget(minute_at, sample_count) KEY(minute_at) VALUES (#{minute}, #{count})",
                Map.of("minute", GlobalWindow.minute(now), "count", sampleBudget)));
        IDeleteWrapper<RiskTables.GlobalBatch, ?, ?> delete = orm.getWrapperFactory().createDeleteWrapper(new RiskTables.GlobalBatch());
        delete.where().eq("projectId", pending.projectId()).eq("batchId", pending.batch().batchId()).lt("createdAt", cutoff);
        orm.delete(ExecuteRequest.ofDelete(RiskTables.GlobalBatch.class).addRequest(delete));
        var receipt = new RiskTables.GlobalBatch();
        receipt.projectId = pending.projectId(); receipt.batchId = pending.batch().batchId();
        receipt.payloadDigest = pending.digest(); receipt.accepted = eventList.size(); receipt.createdAt = now;
        orm.insert(ExecuteRequest.ofInsert(RiskTables.GlobalBatch.class)
                .addRequest(orm.getWrapperFactory().createInsertWrapper(receipt)));
        return new Committed(new Ack(eventList.size(), false), states);
    }

    private Map<BucketKey, Summary> loadSummaries(String project, Set<BucketKey> keys) {
        var result = new LinkedHashMap<BucketKey, Summary>();
        keys.forEach(key -> result.put(key, new Summary()));
        if (keys.isEmpty()) return result;
        var params = new HashMap<String, Object>(); params.put("project", project);
        var tuples = new ArrayList<String>();
        for (var key : keys) {
            int i = tuples.size();
            params.put("user" + i, key.userId()); params.put("bucket" + i, key.at());
            tuples.add("(#{project},#{user" + i + "},#{bucket" + i + "})");
        }
        // Include the complete primary key so H2 can seek each tuple instead of scanning the project.
        var rows = orm.select(QueryRequest.newInstance("""
                SELECT user_id AS "userId", bucket_at AS "bucketAt", request_count AS "requestCount",
                       failures AS "failures", denied AS "denied", suspected AS "suspected", details_json AS "details"
                FROM risk_global_summary WHERE (project_id, user_id, bucket_at) IN (
                """ + String.join(",", tuples) + ")",
                params, RiskTables.GlobalSummary.class).cache(false).autoUpdate(false));
        rows.forEach(row -> result.put(new BucketKey(row.userId, row.bucketAt), readSummary(row)));
        return result;
    }

    private Map<Key, GlobalWindow> loadStates(String project, Set<String> users, long cutoff) {
        var result = new LinkedHashMap<Key, GlobalWindow>();
        var missing = new LinkedHashSet<String>();
        for (String user : users) {
            var key = new Key(project, user);
            var state = cache.get(key);
            if (state == null) missing.add(user);
            else result.put(key, state.copy());
        }
        if (!missing.isEmpty()) {
            ISelectWrapper<RiskTables.GlobalState, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.GlobalState());
            select.where().eq("projectId", project).gte("updatedAt", cutoff).in("userId", missing);
            orm.select(QueryRequest.newInstance(RiskTables.GlobalState.class, select).cache(false).autoUpdate(false))
                    .forEach(row -> result.put(new Key(project, row.userId), row.state));
            missing.forEach(user -> result.putIfAbsent(new Key(project, user), new GlobalWindow()));
        }
        return result;
    }

    private void saveSummaries(String project, Map<BucketKey, Summary> summaries) {
        if (summaries.isEmpty()) return;
        var write = ExecuteRequest.ofUpdate(RiskTables.GlobalSummary.class);
        summaries.forEach((key, summary) -> write.addRequest(new CommandNode("""
                MERGE INTO risk_global_summary(project_id, user_id, bucket_at, request_count, failures, denied, suspected, details_json)
                KEY(project_id, user_id, bucket_at) VALUES (#{project}, #{user}, #{bucket}, #{count}, #{failures}, #{denied}, #{suspected}, #{details})
                """, Map.of("project", project, "user", key.userId(), "bucket", key.at(), "count", summary.count,
                "failures", summary.failures, "denied", summary.denied, "suspected", summary.suspected, "details", summary), ParseType.update)));
        orm.update(write);
    }

    private void saveStates(Map<Key, GlobalWindow> states) {
        if (states.isEmpty()) return;
        var write = ExecuteRequest.ofUpdate(RiskTables.GlobalState.class);
        states.forEach((key, state) -> write.addRequest(new CommandNode("""
                MERGE INTO risk_global_state(project_id, user_id, updated_at, state_json)
                KEY(project_id, user_id) VALUES (#{project}, #{user}, #{updated}, #{state})
                """, Map.of("project", key.projectId(), "user", key.userId(), "updated", state.updatedAt,
                "state", state), ParseType.update)));
        orm.update(write);
    }

    private void saveSamples(String project, List<Sample> samples) {
        if (samples.isEmpty()) return;
        var write = ExecuteRequest.ofInsert(RiskTables.GlobalSample.class);
        for (var sample : samples) {
            var event = sample.event();
            var row = new RiskTables.GlobalSample();
            row.projectId = project; row.userId = event.userId(); row.eventId = event.eventId(); row.action = event.action();
            row.resourceType = event.resourceType(); row.resourceId = event.resourceId(); row.route = event.route(); row.method = event.method();
            row.occurredAt = event.occurredAt(); row.durationMs = event.durationMs(); row.responseStatus = event.status(); row.sampleKind = sample.kind();
            row.ip = event.ip();
            write.addRequest(orm.getWrapperFactory().createInsertWrapper(row));
        }
        orm.insert(write);
    }

    private Map<String, Set<String>> actionNames(String project, List<UserCounts> users, Range range) {
        var result = new HashMap<String, Set<String>>();
        if (users.isEmpty()) return result;
        ISelectWrapper<RiskTables.GlobalSummary, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.GlobalSummary());
        select.where().eq("projectId", project).gte("bucketAt", range.from()).lt("bucketAt", range.to())
                .in("userId", users.stream().map(user -> user.userId).toList());
        orm.select(QueryRequest.newInstance(RiskTables.GlobalSummary.class, select).cache(false).autoUpdate(false))
                .forEach(row -> {
                    Summary summary = row.details;
                    var actions = result.computeIfAbsent(row.userId, ignored -> new HashSet<>());
                    actions.addAll(summary.actions.keySet());
                    if (summary.otherActions > 0) actions.add(OTHER);
                });
        return result;
    }

    private Summary readSummary(RiskTables.GlobalSummary row) {
        Summary result = row.details;
        result.count = row.requestCount; result.failures = row.failures; result.denied = row.denied;
        result.suspected = row.suspected;
        return result;
    }

    private Range range(long from, long to) {
        long now = clock.millis();
        long cutoff = now - RETENTION;
        if (from < cutoff - 60_000 || to <= Math.max(from, cutoff) || to > now + 60_000 || to - from > RETENTION) {
            throw bad("请选择最近 24 小时内的有效时间范围");
        }
        long first = Math.max(bucket(from), ceilBucket(cutoff));
        long last = Math.max(first, ceilBucket(to));
        return new Range(first, last, Math.max(from, cutoff), to);
    }

    private <T> T limited(Supplier<T> task) {
        if (!analyticsSlots.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "分析请求较多，请稍后重试");
        try { return task.get(); } finally { analyticsSlots.release(); }
    }

    private static Batch validateShape(Batch input) {
        if (input == null) throw bad("批次不能为空");
        required(input.batchId(), "batchId", 128);
        if (input.events() == null || input.events().isEmpty() || input.events().size() > 200) throw bad("每批 events 必须为 1 至 200 条");
        var ids = new HashSet<String>();
        for (Event event : input.events()) {
            if (event == null) throw bad("events 不能包含空事件");
            required(event.eventId(), "eventId", 128); required(event.userId(), "userId", 128);
            required(event.action(), "action", 64); optional(event.resourceType(), "resourceType", 64);
            optional(event.ip(), "ip", 45);
            optional(event.resourceId(), "resourceId", 256); required(event.route(), "route", 256);
            required(event.method(), "method", 16);
            if (OTHER.equals(event.action())) throw bad("action 使用了保留值");
            if (!ids.add(event.eventId())) throw bad("同一批次 eventId 不能重复");
            if (event.durationMs() < 0 || event.durationMs() > RETENTION) throw bad("durationMs 超出范围");
            if (event.status() < 100 || event.status() > 599) throw bad("status 必须是有效 HTTP 状态码");
        }
        return new Batch(input.batchId(), List.copyOf(input.events()));
    }

    private static void required(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0) {
            throw bad(name + " 不能为空、包含空字符或超过 " + max + " 字符");
        }
    }
    private static void optional(String value, String name, int max) { if (value != null) required(value, name, max); }
    private static int offset(int page, int size, int max) {
        if (page < 1 || size < 1 || size > max || ((long) page - 1) * size > Integer.MAX_VALUE) throw bad("分页参数超出范围");
        return (page - 1) * size;
    }
    private static long bucket(long at) { return Math.floorDiv(at, SUMMARY_BUCKET) * SUMMARY_BUCKET; }
    private static long ceilBucket(long at) { long floor = bucket(at); return floor == at ? floor : floor + SUMMARY_BUCKET; }
    private boolean online(Event event, long now) {
        return event.occurredAt() >= now - settings.getOnlineMaxDelay().toMillis()
                && GlobalWindow.minute(event.occurredAt()) >= GlobalWindow.minute(now) - 4 * GlobalWindow.MINUTE;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("无法序列化风控数据", error); }
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "上报暂不可用，请保留 batchId 和原内容重试");
    }
}

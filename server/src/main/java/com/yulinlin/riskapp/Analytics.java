package com.yulinlin.riskapp;

import com.yulinlin.data.core.request.QueryRequest;
import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.riskapp.EventStore.SubjectKey;
import com.yulinlin.riskapp.EventStore.HistoryItem;
import com.yulinlin.security.risk.RiskModels.Subject;
import com.yulinlin.security.risk.RiskProperties;
import com.yulinlin.security.risk.RiskReplay;
import com.yulinlin.security.risk.RiskReport;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class Analytics {
    record User(String userId, long count, boolean suspected, boolean blacklisted, long switches, long quickSwitches) {}
    record Page<T>(List<T> items, int page, int pageSize, boolean hasMore) {}
    record Report(RiskReport report, boolean blacklisted) {}
    private record Counts(String userId, long count, boolean suspected, boolean blacklisted) {}
    private record Switches(long different, long quick) {}
    private record Range(long from, long to) {}

    public static class CountRow {
        public String userId;
        public Long count;
        public Integer hit;
        public Integer blocked;
    }

    public static class SwitchRow {
        public String userId;
        public Long different;
        public Long quick;
    }

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private final RouteSession orm;
    private final EventStore store;
    private final Blacklists blacklists;
    private final RiskReplay replay;
    private final Clock clock;
    private final Semaphore slots;
    private final int reportLimit;
    private final long switchGap;

    Analytics(RouteSession orm, EventStore store, Blacklists blacklists, RiskProperties rules, Clock clock,
              @Value("${risk.analytics-concurrency}") int concurrency, @Value("${risk.report-limit}") int reportLimit) {
        if (concurrency < 1 || reportLimit < 1 || reportLimit > 200_000) throw new IllegalArgumentException("分析容量无效");
        this.orm = orm;
        this.store = store;
        this.blacklists = blacklists;
        this.clock = clock;
        this.reportLimit = reportLimit;
        this.replay = new RiskReplay(rules);
        this.slots = new Semaphore(concurrency);
        this.switchGap = rules.getBehaviors().get(Inputs.BEHAVIOR).getFastSwitch().getNormal().getGap().toMillis();
    }

    Page<User> users(String projectId, long from, long to, String userId, long minCount, int page, int size) {
        var range = retainedRange(from, to);
        if (userId != null) Inputs.text(userId, "userId", 128);
        if (minCount < 0) throw Inputs.bad("历史次数不能为负数");
        int offset = Inputs.offset(page, size, 50);
        return limited(() -> listUsers(projectId, range.from(), range.to(), userId, minCount, page, size, offset));
    }

    private Page<User> listUsers(String projectId, long from, long to, String userId, long minCount, int page, int size, int offset) {
        var args = new HashMap<String, Object>(Map.of("project", projectId, "behavior", Inputs.BEHAVIOR,
                "from", from, "to", to, "minimum", userId == null ? minCount : 0L, "size", size + 1, "offset", offset));
        String byUser = userId == null ? "" : " AND e.user_id = #{user}";
        if (userId != null) args.put("user", userId);
        var request = QueryRequest.newInstance("""
                SELECT e.user_id AS "userId", COUNT(*) AS "count",
                       MAX(CASE WHEN e.verdict_status = 'SUSPECTED' THEN 1 ELSE 0 END) AS "hit",
                       MAX(CASE WHEN b.user_id IS NULL THEN 0 ELSE 1 END) AS "blocked"
                FROM risk_event e LEFT JOIN risk_blacklist b ON b.project_id = e.project_id AND b.user_id = e.user_id
                WHERE e.project_id = #{project} AND e.behavior_type = #{behavior}
                  AND e.occurred_at >= #{from} AND e.occurred_at < #{to}
                """ + byUser + " GROUP BY e.user_id HAVING COUNT(*) > #{minimum} ORDER BY \"count\" DESC, e.user_id LIMIT #{size} OFFSET #{offset}",
                args, CountRow.class).cache(false).autoUpdate(false);
        var rows = orm.select(request).stream().map(row -> new Counts(row.userId, row.count, row.hit != 0, row.blocked != 0)).toList();
        var visible = rows.subList(0, Math.min(size, rows.size()));
        var switches = visible.isEmpty() ? Map.<String, Switches>of() : switches(projectId, from, to, visible);
        var items = visible.stream().map(row -> {
            var stats = switches.getOrDefault(row.userId(), new Switches(0, 0));
            return new User(row.userId(), row.count(), row.suspected(), row.blacklisted(), stats.different(), stats.quick());
        }).toList();
        return new Page<>(items, page, size, rows.size() > size);
    }

    private Map<String, Switches> switches(String projectId, long from, long to, List<Counts> users) {
        var args = new HashMap<String, Object>(Map.of("gap", switchGap, "project", projectId,
                "behavior", Inputs.BEHAVIOR, "from", from, "to", to));
        var usersSql = new StringJoiner(", ");
        for (int i = 0; i < users.size(); i++) {
            String parameter = "user" + i;
            usersSql.add("#{" + parameter + "}");
            args.put(parameter, users.get(i).userId());
        }
        var counts = new HashMap<String, Switches>();
        var request = QueryRequest.newInstance("""
                SELECT user_id AS "userId",
                    SUM(CASE WHEN previous_resource <> resource_id THEN 1 ELSE 0 END) AS "different",
                    SUM(CASE WHEN previous_resource <> resource_id AND occurred_at - previous_at <= #{gap} THEN 1 ELSE 0 END) AS "quick"
                FROM (
                    SELECT user_id, resource_id, occurred_at,
                           LAG(resource_id) OVER (PARTITION BY user_id ORDER BY occurred_at, id) AS previous_resource,
                           LAG(occurred_at) OVER (PARTITION BY user_id ORDER BY occurred_at, id) AS previous_at
                    FROM risk_event WHERE project_id = #{project} AND behavior_type = #{behavior}
                      AND occurred_at >= #{from} AND occurred_at < #{to}
                """ + " AND user_id IN (" + usersSql + ")) t GROUP BY user_id", args, SwitchRow.class)
                .cache(false).autoUpdate(false);
        for (var row : orm.select(request)) counts.put(row.userId, new Switches(row.different, row.quick));
        return counts;
    }

    Report report(String projectId, String userId, long from, long to) {
        Inputs.text(userId, "userId", 128);
        var range = retainedRange(from, to);
        return limited(() -> {
            var key = new SubjectKey(projectId, userId, Inputs.BEHAVIOR);
            var events = store.replayEvents(key, range.from(), range.to(), reportLimit + 1);
            if (events.size() > reportLimit) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "事件过多，请缩短回放时段");
            var report = replay.report(new Subject(userId, Inputs.BEHAVIOR), range.from(), range.to(), DISPLAY_ZONE, events);
            return new Report(report, blacklists.contains(projectId, userId));
        });
    }

    Page<HistoryItem> history(String projectId, String userId, long from, long to, int page, int size) {
        Inputs.text(userId, "userId", 128);
        var range = retainedRange(from, to);
        int offset = Inputs.offset(page, size, 200);
        return limited(() -> {
            var rows = store.history(new SubjectKey(projectId, userId, Inputs.BEHAVIOR),
                    range.from(), range.to(), size + 1, offset);
            var items = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
            return new Page<>(items, page, size, rows.size() > size);
        });
    }

    private Range retainedRange(long from, long to) {
        long now = clock.millis();
        long start = Math.max(from, now - Inputs.RETENTION);
        // A small grace interval keeps a rolling retention filter usable while requesting the next page.
        if (from < now - Inputs.RETENTION - 60_000 || to <= start || to > now + 60_000 || to - from > Inputs.RETENTION) {
            throw Inputs.bad("请选择最近 24 小时内的有效时间范围");
        }
        return new Range(start, to);
    }

    private <T> T limited(Supplier<T> query) {
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "分析请求较多，请稍后重试");
        try {
            return query.get();
        } finally {
            slots.release();
        }
    }
}

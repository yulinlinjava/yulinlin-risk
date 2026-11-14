package com.yulinlin.riskapp.global;

import com.yulinlin.data.core.request.ExecuteRequest;
import com.yulinlin.data.core.request.QueryRequest;
import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.riskapp.schema.model.RiskTables;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Short-lived complete event details; aggregate history has its own longer retention. */
@Service
public class GlobalEventLog {
    public static final long RETENTION = Duration.ofHours(12).toMillis();
    public record Entry(String eventId, String userId, String action, String resourceId,
                        String ip, String route, long occurredAt) {}
    public record Page(List<Entry> items, boolean hasMore, String nextCursor, long from, long to) {}
    private record Cursor(long occurredAt, long id) {}

    private final RouteSession orm;
    private final Clock clock;

    public GlobalEventLog(RouteSession orm, Clock clock) {
        this.orm = orm;
        this.clock = clock;
    }

    // Called inside the ingestion transaction, after checking its batch receipt.
    void insert(String project, List<GlobalRiskService.Event> events, long now) {
        var write = ExecuteRequest.ofInsert(RiskTables.GlobalEvent.class);
        int count = 0;
        for (var event : events) {
            if (event.occurredAt() < now - RETENTION) continue;
            var row = new RiskTables.GlobalEvent();
            row.projectId = project;
            row.eventId = event.eventId();
            row.userId = event.userId();
            row.action = event.action();
            row.resourceId = event.resourceId();
            row.ip = event.ip();
            row.route = event.route();
            row.occurredAt = event.occurredAt();
            write.addRequest(orm.getWrapperFactory().createInsertWrapper(row));
            count++;
        }
        // The ORM groups matching statements into JDBC batches of 128 rows on this transaction's connection.
        if (count > 0) orm.insert(write);
    }

    Page page(String project, String userId, Long from, Long to, String cursor, int pageSize) {
        if (pageSize < 1 || pageSize > 200) throw bad("pageSize 必须为 1 至 200");
        if (userId != null && (userId.isBlank() || userId.length() > 128 || userId.indexOf('\0') >= 0)) {
            throw bad("userId 不能为空、包含空字符或超过 128 字符");
        }
        long now = clock.millis();
        long requestedFrom = from == null ? now - RETENTION : from;
        long until = to == null ? now + 1 : to;
        if (requestedFrom < 0 || until <= requestedFrom || until > now + 60_000) {
            throw bad("请选择有效的事件时间范围");
        }
        long since = Math.max(requestedFrom, now - RETENTION);
        Cursor before = cursor == null ? null : cursor(cursor);
        // A page opened earlier may have aged out entirely while the operator was browsing.
        if (since >= until) return new Page(List.of(), false, null, since, until);
        int limit = pageSize + 1;
        var rows = new ArrayList<RiskTables.GlobalEvent>(limit);
        if (before == null) {
            rows.addAll(select(project, userId, since, until - 1, null, limit));
        } else {
            // H2 cannot seek a row-tuple inequality efficiently, especially with equal timestamps.
            // Seek the remaining IDs at this millisecond, then fill the page from earlier milliseconds.
            if (before.occurredAt() >= since && before.occurredAt() < until) {
                rows.addAll(select(project, userId, before.occurredAt(), before.occurredAt(),
                        before.id() - 1, limit));
            }
            if (rows.size() < limit) {
                rows.addAll(select(project, userId, since, Math.min(until - 1, before.occurredAt() - 1),
                        null, limit - rows.size()));
            }
        }
        boolean hasMore = rows.size() > pageSize;
        var visible = rows.subList(0, Math.min(rows.size(), pageSize));
        String next = hasMore ? visible.getLast().occurredAt + ":" + visible.getLast().id : null;
        var items = visible.stream().map(row -> new Entry(row.eventId, row.userId, row.action,
                row.resourceId, row.ip, row.route, row.occurredAt)).toList();
        return new Page(items, hasMore, next, since, until);
    }

    private List<RiskTables.GlobalEvent> select(String project, String userId, long from, long through, Long maxId, int limit) {
        if (through < from) return List.of();
        var params = new HashMap<String, Object>(Map.of("project", project, "from", from,
                "through", through, "limit", limit));
        String byUser = "";
        if (userId != null) {
            byUser = " AND user_id = #{user}";
            params.put("user", userId);
        }
        String byTime = "occurred_at >= #{from} AND occurred_at <= #{through}";
        if (maxId != null) {
            byTime = "occurred_at = #{from} AND id <= #{maxId}";
            params.put("maxId", maxId);
        }
        // Include the fixed prefix so H2 can satisfy the ordering directly from our DESC index.
        String ordering = userId == null ? "project_id, occurred_at DESC, id DESC"
                : "project_id, user_id, occurred_at DESC, id DESC";
        return orm.select(QueryRequest.newInstance("""
                SELECT id AS "id", event_id AS "eventId", user_id AS "userId", action AS "action",
                       resource_id AS "resourceId", ip AS "ip", route AS "route", occurred_at AS "occurredAt"
                FROM risk_global_event
                WHERE project_id = #{project} AND
                """ + byTime + byUser + " ORDER BY " + ordering + " LIMIT #{limit}",
                params, RiskTables.GlobalEvent.class).cache(false).autoUpdate(false));
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 5_000)
    public void cleanExpired() {
        long cutoff = clock.millis() - RETENTION;
        // Each bounded delete commits separately; do not hold one long transaction over the entire backlog.
        for (int batch = 0; batch < 8; batch++) {
            int deleted = orm.update(ExecuteRequest.newInstance(
                    "DELETE FROM risk_global_event WHERE occurred_at <= #{lastExpired} LIMIT 2000",
                    Map.of("lastExpired", cutoff - 1)));
            if (deleted < 2000) break;
        }
    }

    private static Cursor cursor(String value) {
        if (!value.matches("[0-9]{1,19}:[0-9]{1,19}")) throw bad("事件翻页游标无效，请刷新列表");
        String[] parts = value.split(":");
        try {
            long at = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id < 1) throw new NumberFormatException();
            return new Cursor(at, id);
        } catch (NumberFormatException error) {
            throw bad("事件翻页游标无效，请刷新列表");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

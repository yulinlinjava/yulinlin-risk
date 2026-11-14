package com.yulinlin.riskapp;

import com.yulinlin.data.core.node.INode;
import com.yulinlin.data.core.request.ExecuteRequest;
import com.yulinlin.data.core.request.QueryRequest;
import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.data.core.wrapper.ISelectWrapper;
import com.yulinlin.data.core.wrapper.impl.GroupWrapper;
import com.yulinlin.data.core.wrapper.impl.SelectWrapper;
import com.yulinlin.riskapp.schema.model.RiskTables;
import com.yulinlin.security.risk.RiskModels.Match;
import com.yulinlin.security.risk.RiskModels.ReplayEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
class EventStore {
    record Event(String eventId, String userId, String behaviorType, String resourceId, long occurredAt) {}
    record SubjectKey(String projectId, String userId, String behaviorType) {}
    record Stored(Event event, long receivedAt, String status, List<Match> matches) {
        Stored { matches = matches == null ? List.of() : List.copyOf(matches); }
    }
    record Insert(String projectId, Stored stored) {}
    record Result(String status, List<Match> matches, Long judgedAt, boolean blacklisted) {}
    record HistoryItem(String eventId, String resourceId, long occurredAt, long receivedAt, String status) {}

    static final String HISTORY_ONLY = "HISTORY_ONLY";
    private final RouteSession orm;

    EventStore(RouteSession orm) {
        this.orm = orm;
    }

    Map<String, Stored> existing(String projectId, Collection<String> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        ISelectWrapper<RiskTables.Event, ?, ?, ?> query = orm.getWrapperFactory().createSelectWrapper(new RiskTables.Event());
        query.where().eq("projectId", projectId).in("eventId", eventIds);
        var found = new HashMap<String, Stored>();
        for (var event : select(query)) {
            Stored row = stored(event);
            found.put(row.event().eventId(), row);
        }
        return found;
    }

    void insert(List<Insert> events) {
        if (events.isEmpty()) return;
        var request = ExecuteRequest.ofInsert(RiskTables.Event.class);
        for (var row : events) {
            Stored stored = row.stored();
            Event event = stored.event();
            var entity = new RiskTables.Event();
            entity.projectId = row.projectId();
            entity.eventId = event.eventId();
            entity.userId = event.userId();
            entity.behaviorType = event.behaviorType();
            entity.resourceId = event.resourceId();
            entity.occurredAt = event.occurredAt();
            entity.receivedAt = stored.receivedAt();
            entity.verdictStatus = stored.status();
            entity.matches = stored.matches().isEmpty() ? null : stored.matches();
            request.addRequest(orm.getWrapperFactory().createInsertWrapper(entity));
        }
        orm.insert(request);
    }

    Stored latest(SubjectKey key, long retainedSince) {
        ISelectWrapper<RiskTables.Event, ?, ?, ?> query = orm.getWrapperFactory().createSelectWrapper(new RiskTables.Event());
        query.where().eq("projectId", key.projectId()).eq("userId", key.userId())
                .eq("behaviorType", key.behaviorType()).gte("occurredAt", retainedSince).ne("verdictStatus", HISTORY_ONLY);
        query.orderByDesc("receivedAt");
        query.orderByDesc("id");
        query.page(1, 1);
        return select(query).stream().map(EventStore::stored).findFirst().orElse(null);
    }

    record Observation(String resourceId, long receivedAt) {}

    Set<String> restorable(String projectId, Collection<String> users, long cutoff) {
        if (users.isEmpty()) return Set.of();
        var query = new GroupWrapper<RiskTables.Event>().table("RISK_EVENT");
        query.aggregations().field("userId", "userId");
        query.where().eq("projectId", projectId).eq("behaviorType", Inputs.BEHAVIOR)
                .ne("verdictStatus", HISTORY_ONLY).gte("receivedAt", cutoff).in("userId", users);
        return Set.copyOf(select(query).stream().map(row -> row.userId).toList());
    }

    List<Observation> recover(SubjectKey key, long start, long idleMillis, int limit) {
        var before = subject(key, "resourceId", "receivedAt");
        before.where().ne("verdictStatus", HISTORY_ONLY).gte("receivedAt", start - idleMillis).lt("receivedAt", start);
        before.orderByDesc("receivedAt").orderByDesc("id").page(1, 1);
        var predecessor = select(before).stream().map(row -> new Observation(row.resourceId, row.receivedAt)).toList();
        var current = subject(key, "resourceId", "receivedAt");
        current.where().ne("verdictStatus", HISTORY_ONLY).gte("receivedAt", start);
        current.orderByAsc("receivedAt").orderByAsc("id").page(1, limit + 1);
        var window = select(current).stream().map(row -> new Observation(row.resourceId, row.receivedAt)).toList();
        var recovered = new ArrayList<Observation>(predecessor.size() + window.size());
        recovered.addAll(predecessor);
        recovered.addAll(window);
        return recovered;
    }

    List<ReplayEvent> replayEvents(SubjectKey key, long from, long to, int size) {
        var query = subject(key, "eventId", "resourceId", "occurredAt");
        query.where().gte("occurredAt", from).lt("occurredAt", to);
        query.orderByAsc("occurredAt").orderByAsc("id").page(1, size);
        return select(query).stream().map(row -> new ReplayEvent(key.userId(), key.behaviorType(), row.resourceId,
                row.eventId, row.occurredAt)).toList();
    }

    List<HistoryItem> history(SubjectKey key, long from, long to, int size, int offset) {
        // The API fetches size + 1 with an offset based on the visible page size.
        var request = QueryRequest.newInstance("""
                SELECT event_id AS "eventId", resource_id AS "resourceId", occurred_at AS "occurredAt",
                       received_at AS "receivedAt", verdict_status AS "verdictStatus" FROM risk_event
                WHERE project_id = #{project} AND user_id = #{user} AND behavior_type = #{behavior}
                  AND occurred_at >= #{from} AND occurred_at < #{to}
                ORDER BY occurred_at DESC, id DESC LIMIT #{size} OFFSET #{offset}
                """, Map.of("project", key.projectId(), "user", key.userId(), "behavior", key.behaviorType(),
                "from", from, "to", to, "size", size, "offset", offset), RiskTables.Event.class).cache(false).autoUpdate(false);
        return orm.select(request).stream().map(row -> new HistoryItem(row.eventId, row.resourceId, row.occurredAt,
                row.receivedAt, row.verdictStatus)).toList();
    }

    int deleteExpired(long cutoff, int size) {
        return orm.update(ExecuteRequest.newInstance("""
                DELETE FROM risk_event WHERE id IN
                    (SELECT id FROM risk_event WHERE occurred_at < #{cutoff} ORDER BY occurred_at, id LIMIT #{size})
                """, Map.of("cutoff", cutoff, "size", size)));
    }

    Result result(Stored stored, boolean blacklisted) {
        if (stored == null) return new Result("NO_DATA", List.of(), null, blacklisted);
        Long judgedAt = HISTORY_ONLY.equals(stored.status()) ? null : stored.receivedAt();
        return new Result(stored.status(), stored.matches(), judgedAt, blacklisted);
    }

    private List<RiskTables.Event> select(INode query) {
        return orm.select(QueryRequest.newInstance(RiskTables.Event.class, query).cache(false).autoUpdate(false));
    }

    private static SelectWrapper<RiskTables.Event> subject(SubjectKey key, String... fields) {
        var query = new SelectWrapper<RiskTables.Event>().table("RISK_EVENT");
        for (String field : fields) query.fields().field(field, field);
        query.where().eq("projectId", key.projectId()).eq("userId", key.userId()).eq("behaviorType", key.behaviorType());
        return query;
    }

    private static Stored stored(RiskTables.Event row) {
        return new Stored(new Event(row.eventId, row.userId, row.behaviorType, row.resourceId, row.occurredAt),
                row.receivedAt, row.verdictStatus, row.matches);
    }
}

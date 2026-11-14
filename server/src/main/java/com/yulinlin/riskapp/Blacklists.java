package com.yulinlin.riskapp;

import com.yulinlin.data.core.request.ExecuteRequest;
import com.yulinlin.data.core.request.QueryRequest;
import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.data.core.wrapper.IDeleteWrapper;
import com.yulinlin.data.core.wrapper.ISelectWrapper;
import com.yulinlin.data.core.wrapper.IUpdateWrapper;
import com.yulinlin.riskapp.schema.model.RiskTables;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class Blacklists {
    record Member(String userId) {}
    record Entry(String userId, String reason, long addedAt) {}
    record Page<T>(List<T> items, int page, int pageSize, long total, long version) {}

    private final RouteSession orm;
    private final Clock clock;

    Blacklists(RouteSession orm, Clock clock) {
        this.orm = orm;
        this.clock = clock;
    }

    boolean contains(String projectId, String userId) {
        ISelectWrapper<RiskTables.Blacklist, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.Blacklist());
        select.where().eq("projectId", projectId).eq("userId", userId);
        select.page(1, 1);
        return !orm.select(QueryRequest.newInstance(RiskTables.Blacklist.class, select).cache(false).autoUpdate(false)).isEmpty();
    }

    Set<String> find(String projectId, Collection<String> users) {
        if (users.isEmpty()) return Set.of();
        ISelectWrapper<RiskTables.Blacklist, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.Blacklist());
        select.where().eq("projectId", projectId).in("userId", users);
        var result = new HashSet<String>();
        orm.select(QueryRequest.newInstance(RiskTables.Blacklist.class, select).cache(false).autoUpdate(false))
                .forEach(row -> result.add(row.userId));
        return result;
    }

    Page<Member> publicPage(String projectId, int page, int size) {
        var result = page(projectId, page, size);
        return new Page<>(result.items().stream().map(entry -> new Member(entry.userId())).toList(),
                page, size, result.total(), result.version());
    }

    Page<Entry> page(String projectId, int page, int size) {
        int offset = Inputs.offset(page, size, 500);
        // One statement gives the page, count and version from the same database view.
        var query = QueryRequest.newInstance("""
                SELECT p.blacklist_version AS "version", (SELECT COUNT(*) FROM risk_blacklist WHERE project_id = p.id) AS "total",
                       b.user_id AS "userId", b.reason AS "reason", b.added_at AS "addedAt"
                FROM risk_project p
                LEFT JOIN (SELECT user_id, reason, added_at FROM risk_blacklist WHERE project_id = #{projectId}
                           ORDER BY user_id LIMIT #{size} OFFSET #{offset}) b ON TRUE
                WHERE p.id = #{projectId} ORDER BY b.user_id
                """, Map.of("projectId", projectId, "size", size, "offset", offset), PageRow.class)
                .cache(false).autoUpdate(false);
        var rows = orm.select(query);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
        var first = rows.getFirst();
        var entries = new ArrayList<Entry>();
        for (var row : rows) {
            if (row.userId != null) entries.add(new Entry(row.userId, row.reason, row.addedAt));
        }
        return new Page<>(List.copyOf(entries), page, size, first.total, first.version);
    }

    void add(String projectId, String userId, String reason) {
        Inputs.text(userId, "userId", 128);
        Inputs.text(reason, "加入原因", 500);
        orm.transaction(() -> {
            lockProject(projectId);
            if (contains(projectId, userId)) return null;
            var row = new RiskTables.Blacklist();
            row.projectId = projectId;
            row.userId = userId;
            row.reason = reason.strip();
            row.addedAt = clock.millis();
            orm.insert(ExecuteRequest.ofInsert(RiskTables.Blacklist.class)
                    .addRequest(orm.getWrapperFactory().createInsertWrapper(row)));
            incrementVersion(projectId);
            return null;
        });
    }

    void remove(String projectId, String userId) {
        Inputs.text(userId, "userId", 128);
        orm.transaction(() -> {
            lockProject(projectId);
            IDeleteWrapper<RiskTables.Blacklist, ?, ?> delete = orm.getWrapperFactory().createDeleteWrapper(new RiskTables.Blacklist());
            delete.where().eq("projectId", projectId).eq("userId", userId);
            if (orm.delete(ExecuteRequest.ofDelete(RiskTables.Blacklist.class).addRequest(delete)) > 0) {
                incrementVersion(projectId);
            }
            return null;
        });
    }

    private void lockProject(String projectId) {
        ISelectWrapper<RiskTables.Project, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.Project());
        select.where().eq("id", projectId);
        select.lock();
        if (orm.select(QueryRequest.newInstance(RiskTables.Project.class, select).cache(false).autoUpdate(false)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
        }
    }

    private void incrementVersion(String projectId) {
        IUpdateWrapper<RiskTables.Project, ?, ?, ?> update = orm.getWrapperFactory().createUpdateWrapper(new RiskTables.Project());
        update.where().eq("id", projectId);
        update.fields().inc("blacklistVersion", 1L);
        orm.update(ExecuteRequest.ofUpdate(RiskTables.Project.class).addRequest(update));
    }

    /** Projection preserves the empty-page row that still carries total and version. */
    public static class PageRow {
        public Long version;
        public Long total;
        public String userId;
        public String reason;
        public Long addedAt;
    }
}

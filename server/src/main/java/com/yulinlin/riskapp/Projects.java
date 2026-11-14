package com.yulinlin.riskapp;

import com.yulinlin.data.core.request.ExecuteRequest;
import com.yulinlin.data.core.request.QueryRequest;
import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.data.core.wrapper.ISelectWrapper;
import com.yulinlin.data.core.wrapper.IUpdateWrapper;
import com.yulinlin.riskapp.schema.model.RiskTables;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@DependsOnDatabaseInitialization
class Projects {
    record Project(String id, String name, long createdAt, String apiKey) {}
    record Created(Project project, String apiKey) {}

    private final RouteSession orm;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, String> projectsByKey = new ConcurrentHashMap<>();

    Projects(RouteSession orm, Clock clock) {
        this.orm = orm;
        this.clock = clock;
    }

    @PostConstruct void loadKeys() {
        ISelectWrapper<RiskTables.Project, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.Project());
        orm.select(QueryRequest.newInstance(RiskTables.Project.class, select).cache(false).autoUpdate(false))
                .forEach(project -> projectsByKey.put(project.keyDigest, project.id));
    }

    Created create(String name) {
        String checkedName = Inputs.text(name, "项目名称", 100).strip();
        String key = newKey();
        String digest = digest(key);
        var project = new Project(UUID.randomUUID().toString(), checkedName, clock.millis(), key);
        var row = new RiskTables.Project();
        row.id = project.id();
        row.name = project.name();
        row.keyDigest = digest;
        row.apiKey = key;
        row.createdAt = project.createdAt();
        return orm.transaction(() -> {
            orm.insert(ExecuteRequest.ofInsert(RiskTables.Project.class)
                    .addRequest(orm.getWrapperFactory().createInsertWrapper(row)));
            // Nested callers must not expose an API key until their outer transaction commits.
            orm.afterCommit(() -> projectsByKey.put(digest, project.id()));
            return new Created(project, key);
        });
    }

    List<Project> list() {
        ISelectWrapper<RiskTables.Project, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.Project());
        select.orderBy("createdAt", false);
        select.orderBy("id", true);
        return orm.select(QueryRequest.newInstance(RiskTables.Project.class, select).cache(false).autoUpdate(false))
                .stream().map(row -> new Project(row.id, row.name, row.createdAt, row.apiKey)).toList();
    }

    /** Admin resets are serialized through commit so their cached credentials change in the same order. */
    synchronized Project resetKey(String projectId) {
        return orm.transaction(() -> {
            ISelectWrapper<RiskTables.Project, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.Project());
            select.where().eq("id", projectId);
            select.lock();
            var rows = orm.select(QueryRequest.newInstance(RiskTables.Project.class, select).cache(false).autoUpdate(false));
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
            var row = rows.getFirst();
            String key = newKey();
            String nextDigest = digest(key);
            IUpdateWrapper<RiskTables.Project, ?, ?, ?> update = orm.getWrapperFactory().createUpdateWrapper(new RiskTables.Project());
            update.where().eq("id", projectId);
            update.fields().field("apiKey", key).field("keyDigest", nextDigest);
            orm.update(ExecuteRequest.ofUpdate(RiskTables.Project.class).addRequest(update));
            orm.afterCommit(() -> {
                projectsByKey.remove(row.keyDigest);
                projectsByKey.put(nextDigest, projectId);
            });
            return new Project(row.id, row.name, row.createdAt, key);
        });
    }

    private String newKey() {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    void requireProject(String id) {
        ISelectWrapper<RiskTables.Project, ?, ?, ?> select = orm.getWrapperFactory().createSelectWrapper(new RiskTables.Project());
        select.where().eq("id", id);
        select.page(1, 1);
        if (orm.select(QueryRequest.newInstance(RiskTables.Project.class, select).cache(false).autoUpdate(false)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
        }
    }

    String authenticate(String key) {
        String id = key == null || key.length() != 43 ? null : projectsByKey.get(digest(key));
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API Key 无效");
        return id;
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

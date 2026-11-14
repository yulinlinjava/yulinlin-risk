package com.yulinlin.riskapp;

import com.yulinlin.riskapp.EventStore.Event;
import com.yulinlin.riskapp.EventStore.Result;
import com.yulinlin.riskapp.EventStore.SubjectKey;
import com.yulinlin.security.annotation.ApiPublic;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@ApiPublic
public class BusinessApi {
    private final Projects projects;
    private final EventWriter writer;
    private final EventStore store;
    private final Blacklists blacklists;
    private final Clock clock;

    BusinessApi(Projects projects, EventWriter writer, EventStore store, Blacklists blacklists, Clock clock) {
        this.projects = projects;
        this.writer = writer;
        this.store = store;
        this.blacklists = blacklists;
        this.clock = clock;
    }

    @PostMapping("/events")
    public CompletableFuture<Result> submit(@RequestHeader(name = "X-API-Key", required = false) String key,
                                     @RequestBody Event event) {
        return writer.submit(projects.authenticate(key), event);
    }

    @GetMapping("/users/{userId}/risk")
    public Result status(@RequestHeader(name = "X-API-Key", required = false) String key,
                  @PathVariable String userId, @RequestParam(defaultValue = Inputs.BEHAVIOR) String behaviorType) {
        String projectId = projects.authenticate(key);
        Inputs.text(userId, "userId", 128);
        Inputs.behavior(behaviorType);
        var latest = store.latest(new SubjectKey(projectId, userId, behaviorType), clock.millis() - Inputs.RETENTION);
        return store.result(latest, blacklists.contains(projectId, userId));
    }

    @GetMapping("/blacklist")
    public Blacklists.Page<Blacklists.Member> blacklist(@RequestHeader(name = "X-API-Key", required = false) String key,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "100") int pageSize) {
        return blacklists.publicPage(projects.authenticate(key), page, pageSize);
    }
}

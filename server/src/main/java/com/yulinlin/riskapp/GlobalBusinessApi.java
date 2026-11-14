package com.yulinlin.riskapp;

import com.yulinlin.riskapp.global.GlobalRiskService;
import com.yulinlin.security.annotation.ApiPublic;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
@ApiPublic
public class GlobalBusinessApi {
    record UserRisk(String status, List<GlobalRiskService.Reason> reasons, Long judgedAt,
                    boolean dataComplete, boolean blacklisted, String coverage) {}
    private final Projects projects;
    private final Blacklists blacklists;
    private final GlobalRiskService risk;

    GlobalBusinessApi(Projects projects, Blacklists blacklists, GlobalRiskService risk) {
        this.projects = projects;
        this.blacklists = blacklists;
        this.risk = risk;
    }

    @PostMapping("/batches")
    public CompletableFuture<GlobalRiskService.Ack> submit(
            @RequestHeader(name = "X-API-Key", required = false) String key,
            @RequestBody GlobalRiskService.Batch batch) {
        return risk.submit(projects.authenticate(key), batch);
    }

    @GetMapping("/users/{userId}/risk")
    public UserRisk user(@RequestHeader(name = "X-API-Key", required = false) String key,
                  @PathVariable String userId) {
        var project = projects.authenticate(key);
        Inputs.text(userId, "userId", 128);
        var result = risk.risk(project, userId);
        return new UserRisk(result.status(), result.reasons(), result.judgedAt(), result.dataComplete(),
                blacklists.contains(project, userId), "SELECTED_ENDPOINTS");
    }
}

package com.yulinlin.riskapp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulinlin.riskapp.global.GlobalRiskService;
import com.yulinlin.riskapp.global.GlobalEventLog;
import com.yulinlin.security.annotation.ApiLimit;
import com.yulinlin.security.annotation.ApiRole;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/api/projects/{projectId}/v2")
@ApiRole("ADMIN")
@ApiLimit(count = 120, interval = "1m")
public class GlobalAdminApi {
    record User(String userId, long count, long failures, int actionCount, boolean suspected, boolean blacklisted) {}
    private final Projects projects;
    private final Blacklists blacklists;
    private final GlobalRiskService risk;
    private final ObjectMapper json;

    GlobalAdminApi(Projects projects, Blacklists blacklists, GlobalRiskService risk, ObjectMapper json) {
        this.projects = projects;
        this.blacklists = blacklists;
        this.risk = risk;
        this.json = json;
    }

    @GetMapping("/users")
    public GlobalRiskService.Page<User> users(@PathVariable String projectId, @RequestParam long from,
            @RequestParam long to, @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "0") long minCount, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        projects.requireProject(projectId);
        var result = risk.users(projectId, from, to, userId, minCount, page, pageSize);
        Set<String> blocked = result.items().isEmpty() ? Set.of()
                : blacklists.find(projectId, result.items().stream().map(GlobalRiskService.User::userId).toList());
        return new GlobalRiskService.Page<>(result.items().stream().map(row -> new User(row.userId(), row.count(),
                row.failures(), row.actionCount(), row.suspected(), blocked.contains(row.userId()))).toList(),
                result.page(), result.hasMore());
    }

    @GetMapping("/report")
    public Map<String, Object> report(@PathVariable String projectId, @RequestParam String userId,
                               @RequestParam long from, @RequestParam long to) {
        projects.requireProject(projectId);
        Map<String, Object> result = json.convertValue(risk.report(projectId, userId, from, to), new TypeReference<>() {});
        result.put("blacklisted", blacklists.contains(projectId, userId));
        return result;
    }

    @GetMapping("/events")
    public GlobalRiskService.Page<GlobalRiskService.HistoryEvent> events(@PathVariable String projectId,
            @RequestParam String userId, @RequestParam long from, @RequestParam long to,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int pageSize) {
        projects.requireProject(projectId);
        return risk.events(projectId, userId, from, to, page, pageSize);
    }

    @GetMapping("/event-log")
    public GlobalEventLog.Page eventLog(@PathVariable String projectId,
            @RequestParam(required = false) String userId, @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int pageSize) {
        projects.requireProject(projectId);
        return risk.eventLog(projectId, userId, from, to, cursor, pageSize);
    }
}

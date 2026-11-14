package com.yulinlin.riskapp;

import com.yulinlin.security.annotation.ApiLimit;
import com.yulinlin.security.annotation.ApiRole;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/api")
@ApiRole("ADMIN")
@ApiLimit(count = 120, interval = "1m")
public class AdminApi {
    record NewProject(String name) {}
    record NewEntry(String userId, String reason) {}
    record Session(String csrfHeader, String csrfToken) {}

    private final Projects projects;
    private final Analytics analytics;
    private final Blacklists blacklists;

    AdminApi(Projects projects, Analytics analytics, Blacklists blacklists) {
        this.projects = projects;
        this.analytics = analytics;
        this.blacklists = blacklists;
    }

    @GetMapping("/session")
    public Session session(CsrfToken csrf) {
        return new Session(csrf.getHeaderName(), csrf.getToken());
    }

    @GetMapping("/projects")
    public List<Projects.Project> projects() {
        return projects.list();
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Projects.Created create(@RequestBody NewProject request) {
        return projects.create(request.name());
    }

    @PostMapping("/projects/{projectId}/reset-key")
    public Projects.Project resetKey(@PathVariable String projectId) {
        return projects.resetKey(projectId);
    }

    @GetMapping("/projects/{projectId}/users")
    public Analytics.Page<Analytics.User> users(@PathVariable String projectId, @RequestParam long from, @RequestParam long to,
                                         @RequestParam(required = false) String userId,
                                         @RequestParam(defaultValue = "400") long minCount,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int pageSize) {
        projects.requireProject(projectId);
        return analytics.users(projectId, from, to, userId, minCount, page, pageSize);
    }

    @GetMapping("/projects/{projectId}/report")
    public Analytics.Report report(@PathVariable String projectId, @RequestParam String userId,
                            @RequestParam long from, @RequestParam long to) {
        projects.requireProject(projectId);
        return analytics.report(projectId, userId, from, to);
    }

    @GetMapping("/projects/{projectId}/events")
    public Analytics.Page<EventStore.HistoryItem> events(@PathVariable String projectId, @RequestParam String userId,
                                                @RequestParam long from, @RequestParam long to,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "50") int pageSize) {
        projects.requireProject(projectId);
        return analytics.history(projectId, userId, from, to, page, pageSize);
    }

    @GetMapping("/projects/{projectId}/blacklist")
    public Blacklists.Page<Blacklists.Entry> blacklist(@PathVariable String projectId,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        return blacklists.page(projectId, page, pageSize);
    }

    @PostMapping("/projects/{projectId}/blacklist")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@PathVariable String projectId, @RequestBody NewEntry request) {
        blacklists.add(projectId, request.userId(), request.reason());
    }

    @DeleteMapping("/projects/{projectId}/blacklist")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String projectId, @RequestParam String userId) {
        blacklists.remove(projectId, userId);
    }
}

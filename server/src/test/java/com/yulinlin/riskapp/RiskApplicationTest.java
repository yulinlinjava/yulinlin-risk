package com.yulinlin.riskapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.riskapp.EventStore.Event;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:risk-test;DB_CLOSE_DELAY=-1",
        "risk.max-subjects=1", "risk.batch-wait-ms=8"
})
@AutoConfigureMockMvc
class RiskApplicationTest {
    private record Admin(MockHttpSession session, String header, String token) {}

    @BeforeAll static void createAdmin(@Autowired AdminAccounts accounts) {
        accounts.register("admin", "test-password-567");
    }

    @TestConfiguration
    static class TestClock {
        @Bean @Primary Clock stableClock() {
            return Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Projects projects;
    @Autowired EventWriter writer;
    @Autowired Blacklists blacklists;
    @Autowired Retention retention;
    @Autowired JdbcTemplate db;
    @Autowired Clock clock;
    @Autowired RouteSession orm;

    @Test void projectKeysBecomeVisibleOnlyAfterTheOuterOrmTransactionCommits() {
        var created = new java.util.concurrent.atomic.AtomicReference<Projects.Created>();
        assertThatThrownBy(() -> orm.transaction(() -> {
            created.set(projects.create("回滚的项目"));
            assertThatThrownBy(() -> projects.authenticate(created.get().apiKey())).hasMessageContaining("401");
            throw new IllegalStateException("abort outer transaction");
        })).hasMessage("abort outer transaction");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_project WHERE id=?", Long.class,
                created.get().project().id())).isZero();
        assertThatThrownBy(() -> projects.authenticate(created.get().apiKey())).hasMessageContaining("401");

        orm.transaction(() -> {
            created.set(projects.create("提交的项目"));
            assertThatThrownBy(() -> projects.authenticate(created.get().apiKey())).hasMessageContaining("401");
            return null;
        });
        assertThat(projects.authenticate(created.get().apiKey())).isEqualTo(created.get().project().id());
    }

    @Test void concurrentBlacklistChangesKeepAnExactVersionAndPreserveStringUserIds() throws Exception {
        String project = projects.create("并发黑名单").project().id();
        var changes = java.util.stream.IntStream.range(0, 20).mapToObj(i -> CompletableFuture.runAsync(() -> {
            String user = String.format("%04d", i);
            blacklists.add(project, user, "原因 ' #{userId}");
            blacklists.add(project, user, "重复添加");
        })).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(changes).get(15, TimeUnit.SECONDS);
        var first = blacklists.page(project, 1, 10);
        var second = blacklists.page(project, 2, 10);
        var empty = blacklists.page(project, 3, 10);
        assertThat(first.total()).isEqualTo(20);
        assertThat(first.version()).isEqualTo(20);
        assertThat(first.items().getFirst().userId()).isEqualTo("0000");
        assertThat(first.items().getFirst().reason()).isEqualTo("原因 ' #{userId}");
        assertThat(second.items().getFirst().userId()).isEqualTo("0010");
        assertThat(second.version()).isEqualTo(first.version());
        assertThat(empty.items()).isEmpty();
        assertThat(empty.total()).isEqualTo(20);
        assertThat(empty.version()).isEqualTo(20);
        blacklists.remove(project, "0000");
        blacklists.remove(project, "0000");
        assertThat(blacklists.page(project, 1, 10).version()).isEqualTo(21);
    }

    @Test void sdkBatchesAreProjectScopedIdempotentAndVisibleInGlobalAdmin() throws Exception {
        var project = projects.create("SDK 多接口接入");
        var other = projects.create("SDK 独立项目");
        long now = clock.millis();
        var events = java.util.List.of(
                java.util.Map.of("eventId", "sdk-1", "userId", "sdk-user", "action", "content-view",
                        "resourceType", "video", "resourceId", "v1", "route", "/videos/{id}",
                        "method", "GET", "occurredAt", now - 1000, "durationMs", 12, "status", 200),
                java.util.Map.of("eventId", "sdk-2", "userId", "sdk-user", "action", "comment-list",
                        "resourceType", "video", "resourceId", "v1", "route", "/videos/{id}/comments",
                        "method", "GET", "occurredAt", now - 900, "durationMs", 20, "status", 200));
        String payload = json.writeValueAsString(java.util.Map.of("batchId", "sdk-batch-1", "events", events));
        mvc.perform(post("/api/v2/batches").contentType("application/json").content(payload))
                .andExpect(status().isUnauthorized());
        var first = mvc.perform(post("/api/v2/batches").header("X-API-Key", project.apiKey())
                .contentType("application/json").content(payload)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(first)).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.duplicate").value(false));
        var repeated = mvc.perform(post("/api/v2/batches").header("X-API-Key", project.apiKey())
                .contentType("application/json").content(payload)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(repeated)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        mvc.perform(get("/api/v2/users/sdk-user/risk").header("X-API-Key", other.apiKey()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_DATA"));
        blacklists.add(project.project().id(), "sdk-user", "人工测试");
        mvc.perform(get("/api/v2/users/sdk-user/risk").header("X-API-Key", project.apiKey()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.blacklisted").value(true))
                .andExpect(jsonPath("$.coverage").value("SELECTED_ENDPOINTS"));
        var admin = login();
        String base = "/admin/api/projects/" + project.project().id() + "/v2";
        mvc.perform(get(base + "/users").session(admin.session()).param("from", Long.toString(now - 300_000))
                        .param("to", Long.toString(now + 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].count").value(2))
                .andExpect(jsonPath("$.items[0].actionCount").value(2))
                .andExpect(jsonPath("$.items[0].suspected").value(false))
                .andExpect(jsonPath("$.items[0].blacklisted").value(true));
        mvc.perform(get(base + "/report").session(admin.session()).param("userId", "sdk-user")
                        .param("from", Long.toString(now - 300_000)).param("to", Long.toString(now + 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.sampled").value(true)).andExpect(jsonPath("$.actions.length()").value(2))
                .andExpect(jsonPath("$.blacklisted").value(true));
        mvc.perform(get(base + "/events").session(admin.session()).param("userId", "sdk-user")
                        .param("from", Long.toString(now - 300_000)).param("to", Long.toString(now + 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test void reportedIpAndOriginalUrlAreStoredAndVisibleInAdminSamples() throws Exception {
        var project = projects.create("IP 行为上报");
        long occurredAt = clock.millis() - 1000;
        var event = java.util.Map.of("eventId", "ip-1", "userId", "0001", "action", "access",
                "resourceId", "video:00123", "ip", "2001:db8::1", "occurredAt", occurredAt,
                "route", "/videos/00123", "method", "EVENT", "durationMs", 0, "status", 200);
        var batch = json.valueToTree(java.util.Map.of("batchId", "ip-batch", "events", java.util.List.of(event)));
        var accepted = mvc.perform(post("/api/v2/batches").header("X-API-Key", project.apiKey())
                .contentType("application/json").content(json.writeValueAsBytes(batch)))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(accepted)).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(1));
        assertThat(db.queryForObject("SELECT ip FROM risk_global_sample WHERE project_id=? AND event_id=?",
                String.class, project.project().id(), "ip-1")).isEqualTo("2001:db8::1");

        var admin = login();
        mvc.perform(get("/admin/api/projects/" + project.project().id() + "/v2/events").session(admin.session())
                        .param("userId", "0001").param("from", Long.toString(occurredAt - 1))
                        .param("to", Long.toString(clock.millis())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].ip").value("2001:db8::1"))
                .andExpect(jsonPath("$.items[0].resourceId").value("video:00123"))
                .andExpect(jsonPath("$.items[0].route").value("/videos/00123"))
                .andExpect(jsonPath("$.items[0].occurredAt").value(occurredAt));

        ((com.fasterxml.jackson.databind.node.ObjectNode) batch.path("events").get(0)).put("ip", "203.0.113.9");
        var changed = mvc.perform(post("/api/v2/batches").header("X-API-Key", project.apiKey())
                .contentType("application/json").content(json.writeValueAsBytes(batch)))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(changed)).andExpect(status().isConflict());
        ((com.fasterxml.jackson.databind.node.ObjectNode) batch.path("events").get(0)).put("ip", "1".repeat(46));
        mvc.perform(post("/api/v2/batches").header("X-API-Key", project.apiKey())
                .contentType("application/json").content(json.writeValueAsBytes(batch))).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_sample WHERE project_id=?",
                Long.class, project.project().id())).isEqualTo(1);
    }

    @Test void adminEventLogRequiresLoginAndSupportsCursorPagingAndProjectIsolation() throws Exception {
        var project = projects.create("完整事件列表");
        var other = projects.create("事件隔离项目");
        var events = java.util.stream.IntStream.range(0, 3).mapToObj(i -> new com.yulinlin.riskapp.global.GlobalRiskService.Event(
                "list-" + i, "event-user", "access", null, "video:" + i, "/videos/" + i,
                "EVENT", clock.millis() - 1000, 0, 200, "203.0.113.9")).toList();
        var batch = new com.yulinlin.riskapp.global.GlobalRiskService.Batch("event-list-batch", events);
        var accepted = mvc.perform(post("/api/v2/batches").header("X-API-Key", project.apiKey())
                .contentType("application/json").content(json.writeValueAsBytes(batch)))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(accepted)).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(3));
        String path = "/admin/api/projects/" + project.project().id() + "/v2/event-log";
        mvc.perform(get(path).header("X-API-Key", project.apiKey())).andExpect(status().is3xxRedirection());
        var admin = login();
        var first = body(mvc.perform(get(path).session(admin.session()).param("pageSize", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items[0].userId").value("event-user"))
                .andExpect(jsonPath("$.items[0].ip").value("203.0.113.9")).andReturn());
        var second = body(mvc.perform(get(path).session(admin.session()).param("pageSize", "2")
                        .param("from", first.path("from").asText()).param("to", first.path("to").asText())
                        .param("cursor", first.path("nextCursor").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false)).andReturn());
        assertThat(second.path("items").get(0).path("eventId").asText()).isNotIn(
                first.path("items").get(0).path("eventId").asText(), first.path("items").get(1).path("eventId").asText());
        mvc.perform(get(path).session(admin.session()).param("userId", "missing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/admin/api/projects/" + other.project().id() + "/v2/event-log").session(admin.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(path).session(admin.session()).param("pageSize", "201")).andExpect(status().isBadRequest());
        mvc.perform(get(path).session(admin.session()).param("cursor", "invalid")).andExpect(status().isBadRequest());
    }

    @Test void adminAndBusinessFlowKeepsProjectsAndBlacklistVersionsSeparate() throws Exception {
        mvc.perform(get("/admin/index.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/blacklist")).andExpect(status().isUnauthorized());
        var admin = login();
        mvc.perform(get("/admin/index.html").session(admin.session())).andExpect(status().isOk());
        mvc.perform(post("/admin/api/projects").session(admin.session()).contentType("application/json")
                .content("{\"name\":\"csrf test\"}")).andExpect(status().isForbidden());
        var created = body(mvc.perform(post("/admin/api/projects").session(admin.session())
                .header(admin.header(), admin.token()).contentType("application/json").content("{\"name\":\"播放业务\"}"))
                .andExpect(status().isCreated()).andReturn());
        String project = created.path("project").path("id").asText();
        String key = created.path("apiKey").asText();
        var other = projects.create("独立项目");
        long now = clock.millis();

        var pending = new ArrayList<CompletableFuture<EventStore.Result>>();
        for (int i = 0; i < 50; i++) pending.add(writer.submit(project, event("burst-" + i, "shared", "video-" + i, now)));
        // Interleave a different project and evict the first subject to exercise H2 window restoration.
        pending.add(writer.submit(other.project().id(), event("burst-0", "shared", "other", now)));
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        assertThat(pending.get(49).get().status()).isEqualTo("OBSERVING");
        var last = event("burst-50", "shared", "video-50", now);
        submit(key, last).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPECTED"))
                .andExpect(jsonPath("$.matches[0].ruleId").value("rapid"));
        submit(key, last).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPECTED"));
        submit(key, event("burst-50", "shared", "different", now)).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_event WHERE project_id = ?", Long.class, project)).isEqualTo(51);

        String adminBase = "/admin/api/projects/" + project;
        mvc.perform(get(adminBase + "/users").session(admin.session()).param("from", Long.toString(now - 60_000))
                        .param("to", Long.toString(now + 1)).param("minCount", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].count").value(51))
                .andExpect(jsonPath("$.items[0].quickSwitches").value(50));
        mvc.perform(get(adminBase + "/report").session(admin.session()).param("userId", "shared")
                        .param("from", Long.toString(now - 60_000)).param("to", Long.toString(now + 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.report.status").value("SUSPECTED"))
                .andExpect(jsonPath("$.report.hits[0].ruleId").value("rapid"));
        mvc.perform(get(adminBase + "/events").session(admin.session()).param("userId", "shared")
                        .param("from", Long.toString(now - 60_000)).param("to", Long.toString(now + 1)).param("pageSize", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eventId").value("burst-50"))
                .andExpect(jsonPath("$.hasMore").value(true));

        mvc.perform(post(adminBase + "/blacklist").session(admin.session()).header(admin.header(), admin.token())
                        .contentType("application/json").content("{\"userId\":\"shared\",\"reason\":\"人工确认\"}"))
                .andExpect(status().isNoContent());
        blacklists.add(project, "aaa", "测试分页");
        mvc.perform(get("/api/blacklist?page=1&pageSize=1").header("X-API-Key", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].userId").value("aaa"))
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/blacklist?page=2&pageSize=1").header("X-API-Key", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].userId").value("shared"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/users/shared/risk").header("X-API-Key", other.apiKey()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OBSERVING"))
                .andExpect(jsonPath("$.blacklisted").value(false));
        submit(key, last).andExpect(status().isOk()).andExpect(jsonPath("$.blacklisted").value(true));
        mvc.perform(delete(adminBase + "/blacklist").session(admin.session()).header(admin.header(), admin.token()).param("userId", "shared"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/blacklist?page=2&pageSize=1").header("X-API-Key", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.version").value(3));
    }

    @Test void historyOnlyEventsUseOriginalTimeAndExpireWithoutClearingBlacklist() throws Exception {
        var project = projects.create("回放业务");
        long now = clock.millis();
        long start = now - 7 * 60 * 60_000L;
        var results = new ArrayList<CompletableFuture<EventStore.Result>>();
        for (int i = 0; i <= 120; i++) {
            results.add(writer.submit(project.project().id(), event("active-" + i, "old", "resource", start + i * 180_000L)));
        }
        for (int i = 0; i < 200; i++) {
            results.add(writer.submit(project.project().id(), event("normal-" + i, "switcher", "resource-" + i, start + i * 4_000L)));
        }
        CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        assertThat(results.getLast().get().status()).isEqualTo("HISTORY_ONLY");
        mvc.perform(get("/api/users/old/risk").header("X-API-Key", project.apiKey()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_DATA"));
        var admin = login();
        mvc.perform(get("/admin/api/projects/" + project.project().id() + "/report").session(admin.session()).param("userId", "old")
                        .param("from", Long.toString(start)).param("to", Long.toString(now)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.report.hits[0].ruleId").value("continuous"));
        mvc.perform(get("/admin/api/projects/" + project.project().id() + "/report").session(admin.session()).param("userId", "switcher")
                        .param("from", Long.toString(start)).param("to", Long.toString(now)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.report.hits[0].ruleId").value("normal"));
        blacklists.add(project.project().id(), "old", "人工确认");
        db.update("UPDATE risk_event SET occurred_at = ? WHERE project_id = ?", now - Inputs.RETENTION - 1, project.project().id());
        retention.clean();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_event WHERE project_id = ?", Long.class, project.project().id())).isZero();
        assertThat(blacklists.contains(project.project().id(), "old")).isTrue();
    }

    @Test void failedCommitDoesNotLeaveUncommittedBehaviorInTheWindow() throws Exception {
        String project = projects.create("失败恢复").project().id();
        long now = clock.millis();
        var accepted = new ArrayList<CompletableFuture<EventStore.Result>>();
        for (int i = 0; i < 50; i++) accepted.add(writer.submit(project, event("ok-" + i, "user", "r-" + i, now)));
        CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        db.execute("ALTER TABLE risk_event ADD CONSTRAINT test_failed_batch CHECK (resource_id <> 'test-force-failure')");
        try {
            assertThatThrownBy(() -> writer.submit(project, event("failed", "user", "test-force-failure", now)).get(5, TimeUnit.SECONDS))
                    .hasRootCauseMessage("503 SERVICE_UNAVAILABLE \"上报暂不可用，请使用原 eventId 重试\"");
        } finally {
            db.execute("ALTER TABLE risk_event DROP CONSTRAINT test_failed_batch");
        }
        assertThat(writer.submit(project, event("after", "user", "r-49", now)).get(5, TimeUnit.SECONDS).status()).isEqualTo("OBSERVING");
        assertThat(writer.submit(project, event("hit", "user", "r-50", now)).get(5, TimeUnit.SECONDS).status()).isEqualTo("SUSPECTED");
    }

    private Admin login() throws Exception {
        MvcResult login = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        var matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(login.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();
        MvcResult loggedIn = mvc.perform(post("/login").session((MockHttpSession) login.getRequest().getSession(false))
                        .param("_csrf", matcher.group(1)).param("username", "admin").param("password", "test-password-567"))
                .andExpect(status().is3xxRedirection()).andReturn();
        var session = (MockHttpSession) loggedIn.getRequest().getSession(false);
        var csrf = body(mvc.perform(get("/admin/api/session").session(session)).andExpect(status().isOk()).andReturn());
        return new Admin(session, csrf.path("csrfHeader").asText(), csrf.path("csrfToken").asText());
    }

    private org.springframework.test.web.servlet.ResultActions submit(String key, Event event) throws Exception {
        var waiting = mvc.perform(post("/api/events").header("X-API-Key", key).contentType("application/json")
                .content(json.writeValueAsString(event))).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(waiting));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private static Event event(String id, String user, String resource, long occurredAt) {
        return new Event(id, user, Inputs.BEHAVIOR, resource, occurredAt);
    }
}

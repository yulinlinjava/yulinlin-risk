package com.yulinlin.riskapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulinlin.data.core.session.RouteSession;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:risk-project-keys-test;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class ProjectKeysTest {
    private static final String PASSWORD = "project-key-test-password";
    private record Form(MockHttpSession session, String csrf) {}
    private record Admin(MockHttpSession session, String header, String csrf) {}

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Projects projects;
    @Autowired Blacklists blacklists;
    @Autowired JdbcTemplate db;
    @Autowired RouteSession orm;
    @Autowired Clock clock;

    @BeforeAll static void createAdmin(@Autowired AdminAccounts accounts) {
        accounts.register("key-admin", PASSWORD);
    }

    @Test void adminCanReadPersistedKeysAndResetWithoutLosingProjectData() throws Exception {
        var created = projects.create("可复制密钥");
        var other = projects.create("独立密钥");
        var project = created.project();
        assertThat(created.apiKey()).hasSize(43);
        assertThat(project.apiKey()).isEqualTo(created.apiKey());
        blacklists.add(project.id(), "blocked-user", "保留黑名单");
        long blacklistVersion = blacklists.page(project.id(), 1, 20).version();

        var restarted = new Projects(orm, clock);
        restarted.loadKeys();
        assertThat(restarted.authenticate(created.apiKey())).isEqualTo(project.id());
        assertThat(restarted.list()).filteredOn(row -> row.id().equals(project.id()))
                .singleElement().extracting(Projects.Project::apiKey).isEqualTo(created.apiKey());

        var admin = login();
        MvcResult listing = mvc.perform(get("/admin/api/projects").session(admin.session()))
                .andExpect(status().isOk()).andReturn();
        assertThat(listing.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode listed = body(listing);
        JsonNode row = java.util.stream.StreamSupport.stream(listed.spliterator(), false)
                .filter(item -> item.path("id").asText().equals(project.id())).findFirst().orElseThrow();
        assertThat(row.path("apiKey").asText()).isEqualTo(created.apiKey());

        JsonNode reset = body(mvc.perform(post(resetUrl(project.id())).session(admin.session())
                        .header(admin.header(), admin.csrf()))
                .andExpect(status().isOk()).andReturn());
        String replacement = reset.path("apiKey").asText();
        assertThat(replacement).hasSize(43).isNotEqualTo(created.apiKey());
        assertThat(reset.path("id").asText()).isEqualTo(project.id());
        assertThat(reset.path("name").asText()).isEqualTo(project.name());
        assertThat(reset.path("createdAt").asLong()).isEqualTo(project.createdAt());
        assertThat(db.queryForObject("SELECT api_key FROM risk_project WHERE id=?", String.class, project.id()))
                .isEqualTo(replacement);
        assertThat(db.queryForObject("SELECT key_digest FROM risk_project WHERE id=?", String.class, project.id()))
                .isEqualTo(Projects.digest(replacement));
        mvc.perform(get("/api/blacklist").header("X-API-Key", created.apiKey())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/blacklist").header("X-API-Key", replacement)).andExpect(status().isOk());
        assertThat(projects.authenticate(other.apiKey())).isEqualTo(other.project().id());
        assertThat(blacklists.contains(project.id(), "blocked-user")).isTrue();
        assertThat(blacklists.page(project.id(), 1, 20).version()).isEqualTo(blacklistVersion);
        var afterRestart = new Projects(orm, clock);
        afterRestart.loadKeys();
        assertThat(afterRestart.authenticate(replacement)).isEqualTo(project.id());
        assertUnauthorized(afterRestart, created.apiKey());
    }

    @Test void listingAndResetRequireAdminAndResetRequiresCsrf() throws Exception {
        var created = projects.create("密钥权限边界");
        String path = resetUrl(created.project().id());
        mvc.perform(get("/admin/api/projects").header("X-API-Key", created.apiKey()))
                .andExpect(status().is3xxRedirection());
        Form anonymous = form();
        mvc.perform(post(path).session(anonymous.session()).param("_csrf", anonymous.csrf())
                        .header("X-API-Key", created.apiKey()))
                .andExpect(status().is3xxRedirection());

        Form viewer = form();
        viewer.session().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                        "viewer", null, List.of(new SimpleGrantedAuthority("ROLE_VIEWER")))));
        mvc.perform(get("/admin/api/projects").session(viewer.session())).andExpect(status().isForbidden());
        mvc.perform(post(path).session(viewer.session()).param("_csrf", viewer.csrf()))
                .andExpect(status().isForbidden());

        var admin = login();
        mvc.perform(post(path).session(admin.session())).andExpect(status().isForbidden());
        mvc.perform(post(resetUrl("missing-project")).session(admin.session()).header(admin.header(), admin.csrf()))
                .andExpect(status().isNotFound());
        assertThat(projects.authenticate(created.apiKey())).isEqualTo(created.project().id());
        assertThat(db.queryForObject("SELECT api_key FROM risk_project WHERE id=?", String.class, created.project().id()))
                .isEqualTo(created.apiKey());
    }

    @Test void digestOnlyLegacyProjectKeepsWorkingUntilItsKeyIsReset() {
        String legacyKey = "l".repeat(43);
        db.update("INSERT INTO risk_project(id,name,key_digest,created_at) VALUES(?,?,?,?)",
                "legacy-key-project", "历史项目", Projects.digest(legacyKey), clock.millis());
        var restarted = new Projects(orm, clock);
        restarted.loadKeys();
        assertThat(restarted.authenticate(legacyKey)).isEqualTo("legacy-key-project");
        assertThat(restarted.list()).filteredOn(row -> row.id().equals("legacy-key-project"))
                .singleElement().extracting(Projects.Project::apiKey).isNull();

        var reset = restarted.resetKey("legacy-key-project");
        assertThat(reset.apiKey()).hasSize(43).isNotEqualTo(legacyKey);
        assertThat(restarted.authenticate(reset.apiKey())).isEqualTo("legacy-key-project");
        assertUnauthorized(restarted, legacyKey);
        assertThat(restarted.list()).filteredOn(row -> row.id().equals("legacy-key-project"))
                .singleElement().extracting(Projects.Project::apiKey).isEqualTo(reset.apiKey());
    }

    @Test void rollbackPreservesTheOldKeyAndConcurrentResetsLeaveOneValidKey() throws Exception {
        var created = projects.create("密钥事务与并发");
        String id = created.project().id();
        var rolledBack = new AtomicReference<Projects.Project>();
        assertThatThrownBy(() -> orm.transaction(() -> {
            rolledBack.set(projects.resetKey(id));
            assertThat(projects.authenticate(created.apiKey())).isEqualTo(id);
            assertUnauthorized(projects, rolledBack.get().apiKey());
            throw new IllegalStateException("rollback key reset");
        })).hasMessage("rollback key reset");
        assertThat(projects.authenticate(created.apiKey())).isEqualTo(id);
        assertUnauthorized(projects, rolledBack.get().apiKey());
        assertThat(db.queryForObject("SELECT api_key FROM risk_project WHERE id=?", String.class, id))
                .isEqualTo(created.apiKey());

        List<Projects.Project> replacements;
        try (var workers = Executors.newFixedThreadPool(4)) {
            Callable<Projects.Project> reset = () -> projects.resetKey(id);
            var futures = workers.invokeAll(List.of(reset, reset, reset, reset), 15, TimeUnit.SECONDS);
            var results = new java.util.ArrayList<Projects.Project>();
            for (var future : futures) results.add(future.get());
            replacements = List.copyOf(results);
        }
        String current = db.queryForObject("SELECT api_key FROM risk_project WHERE id=?", String.class, id);
        assertThat(replacements).extracting(Projects.Project::apiKey).doesNotHaveDuplicates().contains(current);
        assertThat(projects.authenticate(current)).isEqualTo(id);
        assertUnauthorized(projects, created.apiKey());
        for (var replacement : replacements) {
            if (!replacement.apiKey().equals(current)) assertUnauthorized(projects, replacement.apiKey());
        }
    }

    private Admin login() throws Exception {
        Form form = form();
        MvcResult result = mvc.perform(post("/login").session(form.session()).param("_csrf", form.csrf())
                        .param("username", "key-admin").param("password", PASSWORD))
                .andExpect(status().isSeeOther()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        JsonNode csrf = body(mvc.perform(get("/admin/api/session").session(session)).andExpect(status().isOk()).andReturn());
        return new Admin(session, csrf.path("csrfHeader").asText(), csrf.path("csrfToken").asText());
    }

    private Form form() throws Exception {
        MvcResult result = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"")
                .matcher(result.getResponse().getContentAsString());
        assertThat(csrf.find()).isTrue();
        return new Form((MockHttpSession) result.getRequest().getSession(false), csrf.group(1));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private static String resetUrl(String id) { return "/admin/api/projects/" + id + "/reset-key"; }

    private static void assertUnauthorized(Projects service, String key) {
        assertThatThrownBy(() -> service.authenticate(key)).hasMessageContaining("401");
    }
}

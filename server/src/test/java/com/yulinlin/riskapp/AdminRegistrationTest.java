package com.yulinlin.riskapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.security.auth.PasswordLogin;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:risk-registration-test;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class AdminRegistrationTest {
    private record Form(MockHttpSession session, String csrf) {}
    private final AtomicInteger registrationClient = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired RouteSession orm;
    @Autowired PasswordEncoder encoder;
    @Autowired Clock clock;
    @Autowired AdminAccounts accounts;

    @Test void firstRegistrationPersistsCredentialsAndClosesPublicSetup() throws Exception {
        assertThat(accounts.initialized()).isFalse();
        assertThat(mvc.perform(get("/admin/")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
        assertThat(mvc.perform(get("/login")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl()).endsWith("/setup");

        MvcResult setup = mvc.perform(get("/setup")).andExpect(status().isOk()).andReturn();
        assertThat(setup.getResponse().getContentAsString()).contains("name=\"username\"", "name=\"password\"", "name=\"confirmPassword\"");
        Form form = form(setup);
        mvc.perform(post("/setup").session(form.session()).param("username", "owner")
                .param("password", "test-password").param("confirmPassword", "test-password"))
                .andExpect(status().isForbidden());
        register(form, "bad user", "test-password", "test-password").andExpect(status().isBadRequest());
        register(form, "owner", "short", "short").andExpect(status().isBadRequest());
        register(form, "owner", "密".repeat(25), "密".repeat(25)).andExpect(status().isBadRequest());
        register(form, "owner", "test-password", "different-password").andExpect(status().isBadRequest());
        assertThat(accounts.initialized()).isFalse();

        String password = "密".repeat(24); // Exactly 72 UTF-8 bytes, the bcrypt limit.
        // Two visitors submitting the first registration must create exactly one account.
        try (var attempts = Executors.newFixedThreadPool(2)) {
            Callable<MvcResult> attempt = () -> register(form, "owner", password, password).andReturn();
            var results = attempts.invokeAll(List.of(attempt, attempt), 10, TimeUnit.SECONDS);
            var first = results.get(0).get().getResponse();
            var second = results.get(1).get().getResponse();
            assertThat(List.of(first.getStatus(), second.getStatus())).containsExactlyInAnyOrder(303, 409);
            assertThat((first.getStatus() == 303 ? first : second).getRedirectedUrl()).endsWith("/login?registered");
        }
        assertThat(accounts.initialized()).isTrue();
        String storedHash = db.queryForObject("SELECT password_hash FROM risk_admin_account WHERE id=1", String.class);
        assertThat(storedHash).isNotEqualTo(password).startsWith("{bcrypt}");
        assertThat(encoder.matches(password, storedHash)).isTrue();
        assertThat(db.queryForObject("SELECT username FROM risk_admin_account WHERE id=1", String.class)).isEqualTo("owner");

        assertThat(mvc.perform(get("/setup")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
        register(form, "replacement", "test-password", "test-password").andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_admin_account", Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT password_hash FROM risk_admin_account WHERE id=1", String.class)).isEqualTo(storedHash);

        Form login = form(mvc.perform(get("/login")).andExpect(status().isOk()).andReturn());
        assertThat(mvc.perform(post("/login").session(login.session()).param("_csrf", login.csrf())
                        .param("username", "owner").param("password", "incorrect-password"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl()).endsWith("/login?error");
        login = form(mvc.perform(get("/login")).andExpect(status().isOk()).andReturn());
        MvcResult authenticated = mvc.perform(post("/login").session(login.session()).param("_csrf", login.csrf())
                        .param("username", "owner").param("password", password))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(authenticated.getResponse().getRedirectedUrl()).endsWith("/admin/index.html");
        mvc.perform(get("/admin/api/projects").session((MockHttpSession) authenticated.getRequest().getSession(false)))
                .andExpect(status().isOk());

        // A new service instance reads the persisted account without any initialization password.
        var restarted = new AdminAccounts(orm, encoder, clock);
        assertThat(restarted.initialized()).isTrue();
        var persistedUser = restarted.authenticate(new PasswordLogin("owner", password, "web"));
        assertThat(persistedUser.getUserId()).isEqualTo("1");
        assertThat(persistedUser.getUsername()).isEqualTo("owner");
        assertThat(persistedUser.getRoles()).containsExactly("ADMIN");
        assertThat(restarted.load("1").getUsername()).isEqualTo("owner");
        assertThat(restarted.load("owner")).isNull();
    }

    private ResultActions register(Form form, String username, String password, String confirmation) throws Exception {
        return mvc.perform(post("/setup").session(form.session()).param("_csrf", form.csrf())
                // Different visitors keep validation and first-registration races independent of IP limits.
                .with(request -> { request.setRemoteAddr("198.51.100." + registrationClient.incrementAndGet()); return request; })
                .param("username", username).param("password", password).param("confirmPassword", confirmation));
    }

    private static Form form(MvcResult result) throws Exception {
        var matcher = Pattern.compile("<input\\b[^>]*name=\"_csrf\"[^>]*>").matcher(result.getResponse().getContentAsString());
        assertThat(matcher.find()).as("form contains a CSRF field").isTrue();
        var value = Pattern.compile("value=\"([^\"]+)\"").matcher(matcher.group());
        assertThat(value.find()).isTrue();
        return new Form((MockHttpSession) result.getRequest().getSession(false), value.group(1));
    }
}

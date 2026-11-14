package com.yulinlin.riskapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulinlin.security.auth.IUserDetails;
import com.yulinlin.security.spring.SpringLoginDetails;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:risk-admin-security-test;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class AdminSecurityTest {
    private static final String PASSWORD = "security-test-password";
    private static final String CONTEXT = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
    private record Form(MockHttpSession session, String csrf) {}

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired AdminAccounts accounts;
    @Autowired Projects projects;
    @Autowired SessionRegistry sessions;

    @BeforeAll static void createAdmin(@Autowired AdminAccounts accounts) {
        accounts.register("security-admin", PASSWORD);
    }

    @Test void loginPersistsCredentialFreeFrameworkIdentityAndChangesSessionId() throws Exception {
        String ip = "192.0.2.11";
        Form form = loginForm(ip);
        String anonymousSessionId = form.session().getId();
        mvc.perform(from(post("/login"), ip).session(form.session())
                        .param("username", "security-admin").param("password", PASSWORD))
                .andExpect(status().isForbidden());

        MockHttpSession session = login(form, ip);
        assertThat(session.getId()).isNotEqualTo(anonymousSessionId);
        var authentication = ((SecurityContext) session.getAttribute(CONTEXT)).getAuthentication();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(IUserDetails.class);
        var user = (IUserDetails) authentication.getPrincipal();
        assertThat(user.getUserId()).isEqualTo("1");
        assertThat(user.getUsername()).isEqualTo("security-admin");
        assertThat(user.getRoles()).containsExactly("ADMIN");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
        String storedHash = db.queryForObject("SELECT password_hash FROM risk_admin_account WHERE id=1", String.class);
        assertThat(json.writeValueAsString(user)).doesNotContain(PASSWORD, storedHash, "password", "credential");
        assertThat(user.getAttribute("passwordHash")).isNull();
        assertThat(authentication.getDetails()).isInstanceOf(SpringLoginDetails.class);
        var details = (SpringLoginDetails) authentication.getDetails();
        assertThat(details.getAuthType()).isEqualTo(accounts.name());
        assertThat(details.getDeviceType()).isEqualTo("web");
        assertThat(details.getProviderDetails()).isNull();
        assertThat(sessions.getSessionInformation(session.getId())).isNotNull();

        // A separate request must recover the framework identity and pass @ApiRole.
        mvc.perform(from(get("/admin/api/projects"), ip).session(session)).andExpect(status().isOk());
    }

    @Test void csrfProtectsAdminWritesAndLogoutInvalidatesTheSession() throws Exception {
        String ip = "192.0.2.12";
        MockHttpSession session = login(loginForm(ip), ip);
        mvc.perform(from(post("/admin/api/projects"), ip).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"csrf-project\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(from(post("/logout"), ip).session(session)).andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();

        MvcResult current = mvc.perform(from(get("/admin/api/session"), ip).session(session))
                .andExpect(status().isOk()).andReturn();
        var csrf = json.readTree(current.getResponse().getContentAsString());
        String csrfHeader = csrf.path("csrfHeader").asText();
        String csrfToken = csrf.path("csrfToken").asText();
        assertThat(csrfHeader).isNotBlank();
        assertThat(csrfToken).isNotBlank();
        mvc.perform(from(post("/admin/api/projects"), ip).session(session).header(csrfHeader, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"csrf-project\"}"))
                .andExpect(status().isCreated());
        String oldSessionId = session.getId();
        mvc.perform(from(post("/logout"), ip).session(session).header(csrfHeader, csrfToken))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location", "/login?logout"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(from(get("/admin/api/projects"), ip).cookie(new Cookie("JSESSIONID", oldSessionId)))
                .andExpect(status().is3xxRedirection());
    }

    @Test void authenticatedNonAdminCannotCallEitherAdminController() throws Exception {
        var context = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                "viewer", null, List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))));
        var session = new MockHttpSession();
        session.setAttribute(CONTEXT, context);
        mvc.perform(from(get("/admin/api/projects"), "192.0.2.13").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(from(get("/admin/api/projects/nonexistent/v2/users"), "192.0.2.13")
                        .session(session).param("from", "0").param("to", "1"))
                .andExpect(status().isForbidden());
    }

    @Test void loginLimitCannotBeBypassedByChangingForwardedIpHeaders() throws Exception {
        String ip = "192.0.2.14";
        for (int attempt = 0; attempt < 10; attempt++) {
            Form form = loginForm(ip);
            mvc.perform(from(post("/login"), ip).session(form.session()).param("_csrf", form.csrf())
                            .header("X-Forwarded-For", "203.0.113." + (attempt + 1))
                            .header("X-Real-IP", "198.51.100." + (attempt + 1))
                            .param("username", "security-admin").param("password", "wrong-password"))
                    .andExpect(status().isSeeOther()).andExpect(header().string("Location", "/login?error"));
        }
        Form form = loginForm(ip);
        MvcResult rejected = mvc.perform(from(post("/login"), ip).session(form.session()).param("_csrf", form.csrf())
                        .header("X-Forwarded-For", "203.0.113.99").header("X-Real-IP", "198.51.100.99")
                        .param("username", "security-admin").param("password", PASSWORD))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After")).andReturn();
        assertThat(Long.parseLong(rejected.getResponse().getHeader("Retry-After"))).isPositive();
        assertThat(form.session().getAttribute(CONTEXT)).isNull();
        login(loginForm("192.0.2.15"), "192.0.2.15");
    }

    @Test void publicBusinessEndpointsStillRequireApiKeyEvenWithAnAdminSession() throws Exception {
        String ip = "192.0.2.16";
        MockHttpSession session = login(loginForm(ip), ip);
        var project = projects.create("public-api-key-test");
        for (String endpoint : List.of("/api/blacklist", "/api/v2/users/example/risk")) {
            mvc.perform(from(get(endpoint), ip)).andExpect(status().isUnauthorized());
            mvc.perform(from(get(endpoint), ip).session(session)).andExpect(status().isUnauthorized());
            mvc.perform(from(get(endpoint), ip).session(session).header("X-API-Key", "x".repeat(43)))
                    .andExpect(status().isUnauthorized());
            MvcResult allowed = mvc.perform(from(get(endpoint), ip).header("X-API-Key", project.apiKey()))
                    .andExpect(status().isOk()).andReturn();
            assertThat(allowed.getRequest().getSession(false)).isNull();
        }
    }

    private Form loginForm(String ip) throws Exception {
        MvcResult result = mvc.perform(from(get("/login"), ip)).andExpect(status().isOk()).andReturn();
        var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"")
                .matcher(result.getResponse().getContentAsString());
        assertThat(csrf.find()).as("login form contains a CSRF token").isTrue();
        return new Form((MockHttpSession) result.getRequest().getSession(false), csrf.group(1));
    }

    private MockHttpSession login(Form form, String ip) throws Exception {
        MvcResult result = mvc.perform(from(post("/login"), ip).session(form.session())
                        .param("_csrf", form.csrf()).param("username", "security-admin").param("password", PASSWORD))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location", "/admin/index.html")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String ip) {
        return request.with(servletRequest -> { servletRequest.setRemoteAddr(ip); return servletRequest; });
    }
}

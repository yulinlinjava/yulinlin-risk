package com.yulinlin.risk.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

class RiskReportInterceptorTest {
    @RestController
    static class Controller {
        @GetMapping("/videos/{id}")
        @RiskReport(action = "content-view", resourceType = "video", resource = "id")
        String video(@PathVariable String id) { return id; }

        @GetMapping("/heartbeat") String heartbeat() { return "ok"; }

        @GetMapping("/search") @RiskReport(action = "search", resourceType = "query", resource = "q")
        String search() { return "ok"; }

        @GetMapping("/failed") @RiskReport(action = "sensitive")
        String failed() { throw new IllegalStateException("business failure"); }

        @GetMapping("/async") @RiskReport(action = "async-read")
        Callable<String> async() { return () -> "ok"; }
    }

    @Test void annotationKeepsExistingMetadataWithoutMeasuringDuration() throws Exception {
        var sent = new LinkedBlockingQueue<RiskBatch>();
        try (var client = client(sent)) {
            var interceptor = new RiskReportInterceptor(client, request -> "user", RiskReportInterceptor::defaultResource);
            var request = new MockHttpServletRequest();
            request.setMethod("POST");
            request.setRemoteAddr("203.0.113.9");
            request.addHeader("X-Forwarded-For", "198.51.100.66");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/videos/{id}");
            var response = new MockHttpServletResponse();
            response.setStatus(403);
            var handler = new HandlerMethod(new Controller(), Controller.class.getDeclaredMethod("video", String.class));
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, java.util.Map.of("id", "video:42"));
            long before = System.currentTimeMillis();
            assertThat(interceptor.preHandle(request, response, handler)).isTrue();
            long after = System.currentTimeMillis();
            Thread.sleep(20);
            assertThat(interceptor.preHandle(request, response, handler)).isTrue();
            interceptor.afterCompletion(request, response, handler, new IllegalStateException("business failure"));
            interceptor.afterCompletion(request, response, handler, null);
            var batch = sent.poll(3, TimeUnit.SECONDS);
            assertThat(batch).isNotNull();
            assertThat(batch.events()).hasSize(1);
            assertThat(batch.events().get(0).resourceId()).isEqualTo("video:42");
            assertThat(batch.events().get(0).occurredAt()).isBetween(before, after);
            assertThat(batch.events().get(0).resourceType()).isEqualTo("video");
            assertThat(batch.events().get(0).route()).isEqualTo("/videos/{id}");
            assertThat(batch.events().get(0).method()).isEqualTo("POST");
            assertThat(batch.events().get(0).status()).isEqualTo(403);
            assertThat(batch.events().get(0).durationMs()).isZero();
            assertThat(batch.events().get(0).ip()).isEqualTo("203.0.113.9");
            assertThat(client.metrics().submitted()).isEqualTo(1);
        }
    }

    @Test void onlyAnnotatedAuthenticatedRequestsReportAndIdentityCannotComeFromParameters() throws Exception {
        var sent = new LinkedBlockingQueue<RiskBatch>();
        try (var client = client(sent)) {
            var mvc = MockMvcBuilders.standaloneSetup(new Controller()).addInterceptors(new RiskReportInterceptor(client,
                    request -> request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName(),
                    RiskReportInterceptor::defaultResource)).build();
            mvc.perform(get("/heartbeat").principal(() -> "actual-user")).andExpect(status().isOk());
            mvc.perform(get("/videos/1")).andExpect(status().isOk());
            mvc.perform(get("/videos/42").principal(() -> "actual-user").param("userId", "forged-user"))
                    .andExpect(status().isOk());
            var batch = sent.poll(3, TimeUnit.SECONDS);
            assertThat(batch).isNotNull();
            assertThat(batch.events()).hasSize(1);
            var event = batch.events().get(0);
            assertThat(event.userId()).isEqualTo("actual-user");
            assertThat(event.resourceId()).isEqualTo("42");
            assertThat(event.route()).isEqualTo("/videos/{id}");
            assertThat(event.action()).isEqualTo("content-view");
            assertThat(event.status()).isEqualTo(200);
            assertThat(client.metrics().submitted()).isEqualTo(1);
        }
    }

    @Test void asyncRedispatchProducesOneEventAndFailedRequestPreservesBusinessException() throws Exception {
        var sent = new LinkedBlockingQueue<RiskBatch>();
        try (var client = client(sent)) {
            var mvc = MockMvcBuilders.standaloneSetup(new Controller()).addInterceptors(new RiskReportInterceptor(client,
                    request -> "user", RiskReportInterceptor::defaultResource)).build();
            var pending = mvc.perform(get("/async")).andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
            var async = sent.poll(3, TimeUnit.SECONDS);
            assertThat(async).isNotNull();
            assertThat(async.events().get(0).action()).isEqualTo("async-read");
            assertThatThrownBy(() -> mvc.perform(get("/failed"))).hasRootCauseMessage("business failure");
            var failed = sent.poll(3, TimeUnit.SECONDS);
            assertThat(failed).isNotNull();
            assertThat(failed.events().get(0).status()).isEqualTo(500);
            assertThat(client.metrics().submitted()).isEqualTo(2);
        }
    }

    @Test void resolverFailureDoesNotBreakBusinessResponse() throws Exception {
        var sent = new LinkedBlockingQueue<RiskBatch>();
        try (var client = client(sent)) {
            var mvc = MockMvcBuilders.standaloneSetup(new Controller()).addInterceptors(new RiskReportInterceptor(client,
                    request -> { throw new IllegalStateException("authentication lookup unavailable"); },
                    RiskReportInterceptor::defaultResource)).build();
            mvc.perform(get("/videos/42")).andExpect(status().isOk());
            assertThat(client.metrics().submitted()).isZero();
            assertThat(client.metrics().dropped()).isEqualTo(1);
        }
    }

    @Test void resourceReadsQueryParametersWithoutConsumingFormBody() throws Exception {
        var sent = new LinkedBlockingQueue<RiskBatch>();
        try (var client = client(sent)) {
            var mvc = MockMvcBuilders.standaloneSetup(new Controller()).addInterceptors(new RiskReportInterceptor(client,
                    request -> "user", RiskReportInterceptor::defaultResource)).build();
            mvc.perform(get(java.net.URI.create("/search?q=hello%20world"))).andExpect(status().isOk());
            var query = sent.poll(3, TimeUnit.SECONDS);
            assertThat(query).isNotNull();
            assertThat(query.events().get(0).resourceId()).isEqualTo("hello world");
            mvc.perform(get("/search").contentType("application/x-www-form-urlencoded").content("q=body-only"))
                    .andExpect(status().isOk());
            var bodyOnly = sent.poll(3, TimeUnit.SECONDS);
            assertThat(bodyOnly).isNotNull();
            assertThat(bodyOnly.events().get(0).resourceId()).isNull();
        }
    }

    private RiskClient client(LinkedBlockingQueue<RiskBatch> sent) {
        var client = new RiskClient(RiskClientTest.config(), new RiskClientTest.FakeTransport() {
            @Override public SendResult send(RiskBatch batch) { sent.add(batch); return new SendResult(200, batch.events().size()); }
        });
        client.start();
        return client;
    }
}

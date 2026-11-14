package com.yulinlin.risk.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class HttpRiskTransportTest {
    @Test void simpleReportingRemainsCompatibleWithTheExistingWireProtocol() throws Exception {
        var original = new RiskEvent("0001", "content-view", "video:00123");
        var batch = new RiskBatch("batch-1", List.of(original));
        var json = new ObjectMapper();
        var payload = json.readTree(json.writeValueAsBytes(batch));
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder("batchId", "events");
        var event = payload.path("events").get(0);
        assertThat(event.fieldNames()).toIterable().containsExactlyInAnyOrder("eventId", "userId", "action", "resourceType",
                "resourceId", "route", "method", "occurredAt", "durationMs", "status", "ip");
        assertThat(event.path("userId").asText()).isEqualTo("0001");
        assertThat(event.path("resourceId").asText()).isEqualTo("video:00123");
        assertThat(event.path("eventId").asText()).isEqualTo(original.eventId());
        assertThat(event.path("occurredAt").asLong()).isEqualTo(original.occurredAt());
        assertThat(event.path("resourceType").isNull()).isTrue();
        assertThat(event.path("route").asText()).isEqualTo("/behavior");
        assertThat(event.path("method").asText()).isEqualTo("EVENT");
        assertThat(event.path("durationMs").asLong()).isZero();
        assertThat(event.path("status").asInt()).isEqualTo(200);
        assertThat(event.path("ip").isNull()).isTrue();
    }

    @Test void explicitEventsKeepTheOldConstructorAndSerializeIpWhenProvided() throws Exception {
        var legacy = new RiskEvent("event-1", "user-1", "view", "video", "v1", "/videos/{id}", "GET", 123L, 0, 200);
        assertThat(legacy.ip()).isNull();
        assertThat(legacy.valid()).isTrue();
        var withIp = new RiskEvent("event-2", "user-1", "view", "video", "v1", "/videos/{id}", "GET", 123L, 0, 200, "2001:db8::1");
        var event = new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(withIp));
        assertThat(event.path("ip").asText()).isEqualTo("2001:db8::1");
        assertThat(event.path("occurredAt").asLong()).isEqualTo(123L);
        assertThat(event.path("route").asText()).isEqualTo("/videos/{id}");
    }

    @Test void boundedResponseCancelsBeforeAccumulatingOversizedBody() {
        var subscription = new Subscription();
        var body = new HttpRiskTransport.LimitedResponseSubscriber(4);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3})));
        body.onNext(List.of(ByteBuffer.wrap(new byte[] {4, 5})));
        assertThat(subscription.cancelled).isTrue();
        assertThatThrownBy(() -> body.getBody().toCompletableFuture().join()).hasRootCauseMessage("Risk response is too large");

        var small = new HttpRiskTransport.LimitedResponseSubscriber(4);
        small.onSubscribe(new Subscription());
        small.onNext(List.of(ByteBuffer.wrap(new byte[] {1}), ByteBuffer.wrap(new byte[] {2, 3, 4})));
        small.onComplete();
        assertThat(small.getBody().toCompletableFuture().join()).containsExactly(1, 2, 3, 4);
    }

    static class Subscription implements Flow.Subscription {
        boolean cancelled;
        @Override public void request(long n) {}
        @Override public void cancel() { cancelled = true; }
    }
}

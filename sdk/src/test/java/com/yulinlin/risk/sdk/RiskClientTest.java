package com.yulinlin.risk.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RiskClientTest {
    @Test void explicitBehaviorFieldsSurviveDelayAndRetryWithoutRewriting() throws Exception {
        var attempts = new CopyOnWriteArrayList<RiskBatch>();
        var settings = config();
        settings.setBatchSize(20);
        settings.setFlushInterval(Duration.ofMillis(300));
        long occurredAt = System.currentTimeMillis() - 60_000;
        String url = "/videos/00123";
        try (var client = new RiskClient(settings, new FakeTransport() {
            @Override public SendResult send(RiskBatch batch) {
                attempts.add(batch);
                return attempts.size() == 1 ? new SendResult(503, 0) : new SendResult(200, batch.events().size());
            }
        })) {
            client.start();
            assertThat(client.tryReport("0001", "video:00123", "2001:db8::1", occurredAt, url)).isTrue();
            assertThat(client.tryReport("0001", "content-view", "video:00123", "203.0.113.9", occurredAt - 1, "/videos/{id}")).isTrue();
            Thread.sleep(80);
            assertThat(attempts).isEmpty();
            await(() -> client.metrics().delivered() == 2);
            assertThat(attempts).hasSize(2);
            assertThat(attempts.get(1)).isSameAs(attempts.get(0));
            var access = attempts.get(0).events().get(0);
            assertThat(access.userId()).isEqualTo("0001");
            assertThat(access.action()).isEqualTo("access");
            assertThat(access.resourceId()).isEqualTo("video:00123");
            assertThat(access.resourceType()).isNull();
            assertThat(access.ip()).isEqualTo("2001:db8::1");
            assertThat(access.occurredAt()).isEqualTo(occurredAt);
            assertThat(access.route()).isEqualTo(url);
            assertThat(access.method()).isEqualTo("EVENT");
            assertThat(access.durationMs()).isZero();
            assertThat(access.status()).isEqualTo(200);
            var classified = attempts.get(1).events().get(1);
            assertThat(classified.action()).isEqualTo("content-view");
            assertThat(classified.ip()).isEqualTo("203.0.113.9");
            assertThat(classified.occurredAt()).isEqualTo(occurredAt - 1);
            assertThat(classified.route()).isEqualTo("/videos/{id}");
            assertThat(access.eventId()).isNotEqualTo(classified.eventId());

            assertThat(client.tryReport("0001", "v1", null, occurredAt, url)).isFalse();
            assertThat(client.tryReport("0001", "v1", " ", occurredAt, url)).isFalse();
            assertThat(client.tryReport("0001", "v1", "1".repeat(46), occurredAt, url)).isFalse();
            assertThat(client.tryReport("0001", "v1", "127.0.0.1\0", occurredAt, url)).isFalse();
            assertThat(client.tryReport("0001", "v1", "127.0.0.1", 0, url)).isFalse();
            assertThat(client.tryReport("0001", "v1", "127.0.0.1", occurredAt, "")).isFalse();
            assertThat(client.metrics().dropped()).isEqualTo(6);
        }
    }

    @Test void simpleReportingGeneratesMetadataOnceAndAllowsResourceLessBehaviors() throws Exception {
        var batches = new LinkedBlockingQueue<RiskBatch>();
        var settings = config();
        settings.setBatchSize(20);
        settings.setFlushInterval(Duration.ofMillis(300));
        try (var client = new RiskClient(settings, new FakeTransport() {
            @Override public SendResult send(RiskBatch batch) { batches.add(batch); return new SendResult(200, batch.events().size()); }
        })) {
            client.start();
            long before = System.currentTimeMillis();
            assertThat(client.tryReport("0001", "content-view", "video:00123")).isTrue();
            assertThat(client.tryReport("0001", "search")).isTrue();
            long after = System.currentTimeMillis();
            assertThat(batches.poll(80, TimeUnit.MILLISECONDS)).isNull();
            var batch = batches.poll(2, TimeUnit.SECONDS);
            assertThat(batch).isNotNull();
            assertThat(batch.events()).hasSize(2);
            var viewed = batch.events().get(0);
            var searched = batch.events().get(1);
            assertThat(viewed.userId()).isEqualTo("0001");
            assertThat(viewed.action()).isEqualTo("content-view");
            assertThat(viewed.resourceId()).isEqualTo("video:00123");
            assertThat(viewed.resourceType()).isNull();
            assertThat(viewed.route()).isEqualTo("/behavior");
            assertThat(viewed.method()).isEqualTo("EVENT");
            assertThat(viewed.durationMs()).isZero();
            assertThat(viewed.status()).isEqualTo(200);
            assertThat(searched.resourceId()).isNull();
            for (var event : batch.events()) {
                assertThat(java.util.UUID.fromString(event.eventId()).toString()).isEqualTo(event.eventId());
                assertThat(event.occurredAt()).isBetween(before, after);
            }
            assertThat(viewed.eventId()).isNotEqualTo(searched.eventId());
            assertThat(client.tryReport("", "content-view", "video:1")).isFalse();
            assertThat(client.tryReport("0001", "__OTHER__", null)).isFalse();
            assertThat(client.tryReport("0001", "content-view", "")).isFalse();
        }
    }

    @Test void lowTrafficWaitsForDelayAndFullBatchSendsBeforeDelay() throws Exception {
        var batches = new LinkedBlockingQueue<RiskBatch>();
        var config = config();
        config.setBatchSize(2);
        config.setFlushInterval(Duration.ofMillis(300));
        try (var client = new RiskClient(config, new FakeTransport() {
            @Override public SendResult send(RiskBatch batch) { batches.add(batch); return new SendResult(200, batch.events().size()); }
        })) {
            client.start();
            var event = event("delayed");
            client.tryReport(event);
            assertThat(batches.poll(80, TimeUnit.MILLISECONDS)).isNull();
            var delayed = batches.poll(2, TimeUnit.SECONDS);
            assertThat(delayed).isNotNull();
            assertThat(delayed.events()).containsExactly(event);
            assertThat(delayed.events().get(0).occurredAt()).isEqualTo(event.occurredAt());
        }
        config.setFlushInterval(Duration.ofSeconds(10));
        try (var client = new RiskClient(config, new FakeTransport() {
            @Override public SendResult send(RiskBatch batch) { batches.add(batch); return new SendResult(200, batch.events().size()); }
        })) {
            client.start();
            client.tryReport(event("full-1"));
            assertThat(batches.poll(80, TimeUnit.MILLISECONDS)).isNull();
            client.tryReport(event("full-2"));
            var full = batches.poll(2, TimeUnit.SECONDS);
            assertThat(full).isNotNull();
            assertThat(full.events()).extracting(RiskEvent::eventId).containsExactly("full-1", "full-2");
        }
    }

    @Test void closeFlushesWithoutWaitingForLongBatchDelay() {
        var sent = new CopyOnWriteArrayList<RiskBatch>();
        var config = config();
        config.setBatchSize(20);
        config.setFlushInterval(Duration.ofSeconds(30));
        var client = new RiskClient(config, new FakeTransport() {
            @Override public SendResult send(RiskBatch batch) { sent.add(batch); return new SendResult(200, batch.events().size()); }
        });
        client.start();
        client.tryReport(event("closing"));
        client.close();
        assertThat(sent).hasSize(1);
        assertThat(client.metrics().delivered()).isEqualTo(1);
        assertThat(client.metrics().dropped()).isZero();
    }

    @Test void retryUsesIdenticalBatchAndEventIds() throws Exception {
        var attempts = new CopyOnWriteArrayList<RiskBatch>();
        var transport = new FakeTransport() {
            @Override public SendResult send(RiskBatch batch) {
                attempts.add(batch);
                return new SendResult(attempts.size() == 1 ? 503 : 200, attempts.size() == 1 ? 0 : batch.events().size());
            }
        };
        var settings = config();
        try (var client = new RiskClient(settings, transport)) {
            client.start();
            long before = System.currentTimeMillis();
            assertThat(client.tryReport("trusted-user", "content-view", "video:v1")).isTrue();
            long after = System.currentTimeMillis();
            await(() -> client.metrics().delivered() == 1);
            assertThat(attempts).hasSize(2);
            assertThat(attempts.get(1)).isSameAs(attempts.get(0));
            assertThat(attempts.get(0).events().get(0).eventId()).isNotBlank();
            assertThat(attempts.get(1).events().get(0).occurredAt()).isBetween(before, after);
            assertThat(client.metrics().sendFailures()).isEqualTo(1);
            assertThat(client.metrics().dropped()).isZero();
        }
    }

    @Test void permanentRejectionDoesNotRetryAndOverloadDoesNotWaitForNetwork() throws Exception {
        var count = new AtomicInteger();
        try (var client = new RiskClient(config(), new FakeTransport() {
            @Override public SendResult send(RiskBatch batch) { count.incrementAndGet(); return new SendResult(400, 0); }
        })) {
            client.start();
            client.tryReport(event("bad"));
            await(() -> client.metrics().dropped() == 1);
            assertThat(count.get()).isEqualTo(1);
        }
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var settings = config();
        settings.setQueueCapacity(1);
        try (var client = new RiskClient(settings, new FakeTransport() {
            @Override public SendResult send(RiskBatch batch) throws InterruptedException {
                entered.countDown();
                release.await(3, TimeUnit.SECONDS);
                return new SendResult(200, batch.events().size());
            }
        })) {
            client.start();
            assertThat(client.tryReport(event("one"))).isTrue();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(client.tryReport(event("two"))).isTrue();
            assertThat(client.tryReport(event("three"))).isFalse();
            assertThat(client.metrics().dropped()).isEqualTo(1);
            release.countDown();
            await(() -> client.metrics().delivered() == 2);
        } finally { release.countDown(); }
    }

    @Test void blacklistOnlyReplacesAfterAllPagesShareVersionAndEmptyClearsIt() {
        var mode = new AtomicInteger();
        var transport = new FakeTransport() {
            @Override public BlacklistPage blacklist(int page, int size) {
                if (mode.get() == 0) return new BlacklistPage(List.of("existing"), 1, 500, 1, 1);
                if (mode.get() == 1) return page == 1
                        ? new BlacklistPage(IntStream.range(0, 500).mapToObj(i -> "u" + i).toList(), 1, 500, 501, 2)
                        : new BlacklistPage(List.of("last"), 2, 500, 501, 3);
                return new BlacklistPage(List.of(), 1, 500, 0, 4);
            }
        };
        try (var client = new RiskClient(config(), transport)) {
            assertThat(client.metrics().blacklistReady()).isFalse();
            assertThat(client.refreshBlacklist()).isTrue();
            assertThat(client.isBlacklisted("existing")).isTrue();
            mode.set(1);
            assertThat(client.refreshBlacklist()).isFalse();
            assertThat(client.isBlacklisted("existing")).isTrue();
            assertThat(client.isBlacklisted("u0")).isFalse();
            assertThat(client.metrics().blacklistRefreshFailures()).isEqualTo(1);
            mode.set(2);
            assertThat(client.refreshBlacklist()).isTrue();
            assertThat(client.isBlacklisted("existing")).isFalse();
        }
    }

    @Test void malformedEventsAreRejectedLocallyAndCloseFlushesQueuedEvents() {
        var sent = new CopyOnWriteArrayList<RiskBatch>();
        var settings = config();
        settings.setBatchSize(20);
        settings.setQueueCapacity(20);
        settings.setFlushInterval(Duration.ofSeconds(1));
        var client = new RiskClient(settings, new FakeTransport() {
            @Override public SendResult send(RiskBatch batch) { sent.add(batch); return new SendResult(200, batch.events().size()); }
        });
        client.start();
        assertThat(client.tryReport(new RiskEvent("x", "", "read", null, null, "/x", "GET", 1, 0, 200))).isFalse();
        for (int i = 0; i < 10; i++) assertThat(client.tryReport(event("e" + i))).isTrue();
        client.close();
        assertThat(sent.stream().mapToInt(batch -> batch.events().size()).sum()).isEqualTo(10);
        assertThat(client.metrics().delivered()).isEqualTo(10);
        assertThat(client.tryReport(event("closed"))).isFalse();
    }

    static RiskClientProperties config() {
        var config = new RiskClientProperties();
        config.setBaseUrl(URI.create("http://127.0.0.1:8080"));
        config.setApiKey("test-key");
        config.setBatchSize(1);
        config.setFlushInterval(Duration.ofMillis(5));
        config.setRetryDelay(Duration.ofMillis(5));
        config.setShutdownTimeout(Duration.ofSeconds(2));
        return config;
    }

    static RiskEvent event(String id) {
        return new RiskEvent(id, "trusted-user", "content-view", "video", "v1", "/videos/{id}", "GET", System.currentTimeMillis(), 2, 200);
    }

    static void await(BooleanSupplier ready) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!ready.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertThat(ready.getAsBoolean()).isTrue();
    }

    static class FakeTransport implements RiskTransport {
        @Override public SendResult send(RiskBatch batch) throws IOException, InterruptedException { return new SendResult(200, batch.events().size()); }
        @Override public BlacklistPage blacklist(int page, int size) throws IOException, InterruptedException { return new BlacklistPage(List.of(), page, size, 0, 1); }
        @Override public JsonNode risk(String user) { return new ObjectMapper().createObjectNode().put("status", "OBSERVING"); }
    }
}

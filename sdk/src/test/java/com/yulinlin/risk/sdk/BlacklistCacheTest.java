package com.yulinlin.risk.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BlacklistCacheTest {
    @Test void firstVersionZeroLoadsAllPagesAndUnchangedVersionOnlyChecksFirstPage() throws Exception {
        var transport = new BlacklistTransport();
        transport.users = users(501);
        try (var client = new RiskClient(RiskClientTest.config(), transport)) {
            assertThat(client.refreshBlacklist()).isTrue();
            assertThat(transport.requestedPages).containsExactly(1, 2);
            assertThat(client.metrics().blacklistReady()).isTrue();
            assertThat(client.isBlacklisted("u500")).isTrue();

            long previousRefresh = client.metrics().lastBlacklistRefreshAt();
            RiskClientTest.await(() -> System.currentTimeMillis() > previousRefresh);
            transport.requestedPages.clear();
            assertThat(client.refreshBlacklist()).isTrue();
            assertThat(transport.requestedPages).containsExactly(1);
            assertThat(client.metrics().lastBlacklistRefreshAt()).isGreaterThan(previousRefresh);
            assertThat(client.metrics().blacklistRefreshFailures()).isZero();
            assertThat(client.isBlacklisted("u500")).isTrue();
            assertThat(client.isBlacklisted("missing")).isFalse();
            assertThat(transport.requestedPages).containsExactly(1);
        }
    }

    @Test void failedRefreshKeepsOldCacheAndVersionUntilAllNewPagesSucceed() {
        var transport = new BlacklistTransport();
        transport.users = List.of("existing");
        transport.version = 1;
        try (var client = new RiskClient(RiskClientTest.config(), transport)) {
            assertThat(client.refreshBlacklist()).isTrue();
            long previousRefresh = client.metrics().lastBlacklistRefreshAt();
            transport.users = users(501);
            transport.version = 2;

            for (int failingPage : List.of(1, 2)) {
                transport.failingPage = failingPage;
                assertThat(client.refreshBlacklist()).isFalse();
                assertThat(client.isBlacklisted("existing")).isTrue();
                assertThat(client.isBlacklisted("u0")).isFalse();
                assertThat(client.metrics().blacklistReady()).isTrue();
                assertThat(client.metrics().lastBlacklistRefreshAt()).isEqualTo(previousRefresh);
            }
            assertThat(client.metrics().blacklistRefreshFailures()).isEqualTo(2);

            transport.failingPage = -1;
            transport.requestedPages.clear();
            assertThat(client.refreshBlacklist()).isTrue();
            assertThat(transport.requestedPages).containsExactly(1, 2);
            assertThat(client.isBlacklisted("existing")).isFalse();
            assertThat(client.isBlacklisted("u500")).isTrue();

            transport.requestedPages.clear();
            assertThat(client.refreshBlacklist()).isTrue();
            assertThat(transport.requestedPages).containsExactly(1);

            transport.version = 3;
            transport.users = List.of();
            assertThat(client.refreshBlacklist()).isTrue();
            assertThat(client.isBlacklisted("u0")).isFalse();
            assertThat(client.isBlacklisted("u500")).isFalse();
            assertThat(client.metrics().blacklistReady()).isTrue();
            transport.requestedPages.clear();
            assertThat(client.refreshBlacklist()).isTrue();
            assertThat(transport.requestedPages).containsExactly(1);
        }
    }

    @Test void failedInitialDownloadDoesNotMarkVersionZeroAsReadyOrSkipRetryPages() {
        var transport = new BlacklistTransport();
        transport.users = users(501);
        transport.failingPage = 2;
        try (var client = new RiskClient(RiskClientTest.config(), transport)) {
            assertThat(client.refreshBlacklist()).isFalse();
            assertThat(client.metrics().blacklistReady()).isFalse();
            assertThat(client.metrics().lastBlacklistRefreshAt()).isZero();
            assertThat(client.isBlacklisted("u0")).isFalse();
            transport.failingPage = -1;
            transport.requestedPages.clear();
            assertThat(client.refreshBlacklist()).isTrue();
            assertThat(transport.requestedPages).containsExactly(1, 2);
            assertThat(client.isBlacklisted("u500")).isTrue();
        }
    }

    @Test void matchingVersionStillRequiresAValidCompleteFirstPageAndConsistentTotal() {
        var transport = new BlacklistTransport();
        transport.users = users(501);
        transport.version = 7;
        var firstPage = transport.users.subList(0, 500);
        var invalidPages = List.of(
                new RiskTransport.BlacklistPage(firstPage, 2, 500, 501, 7),
                new RiskTransport.BlacklistPage(firstPage, 1, 100, 501, 7),
                new RiskTransport.BlacklistPage(firstPage, 1, 500, 502, 7),
                new RiskTransport.BlacklistPage(firstPage.subList(0, 499), 1, 500, 501, 7),
                new RiskTransport.BlacklistPage(Collections.nCopies(500, "u0"), 1, 500, 501, 7),
                new RiskTransport.BlacklistPage(List.of(" "), 1, 500, 501, 7),
                new RiskTransport.BlacklistPage(List.of(), 1, 500, 0, 7));
        try (var client = new RiskClient(RiskClientTest.config(), transport)) {
            assertThat(client.refreshBlacklist()).isTrue();
            long previousRefresh = client.metrics().lastBlacklistRefreshAt();
            for (var invalid : invalidPages) {
                transport.firstPageOverride = invalid;
                transport.requestedPages.clear();
                assertThat(client.refreshBlacklist()).isFalse();
                assertThat(transport.requestedPages).containsExactly(1);
                assertThat(client.isBlacklisted("u0")).isTrue();
                assertThat(client.isBlacklisted("u500")).isTrue();
                assertThat(client.metrics().lastBlacklistRefreshAt()).isEqualTo(previousRefresh);
            }
            assertThat(client.metrics().blacklistRefreshFailures()).isEqualTo(invalidPages.size());
            transport.firstPageOverride = null;
            transport.requestedPages.clear();
            assertThat(client.refreshBlacklist()).isTrue();
            assertThat(transport.requestedPages).containsExactly(1);
        }
    }

    private static List<String> users(int count) {
        return IntStream.range(0, count).mapToObj(i -> "u" + i).toList();
    }

    private static class BlacklistTransport extends RiskClientTest.FakeTransport {
        final List<Integer> requestedPages = new ArrayList<>();
        List<String> users = List.of();
        long version;
        int failingPage = -1;
        BlacklistPage firstPageOverride;

        @Override public BlacklistPage blacklist(int page, int size) throws IOException {
            requestedPages.add(page);
            if (page == failingPage) throw new IOException("Simulated unavailable page");
            if (page == 1 && firstPageOverride != null) return firstPageOverride;
            int from = Math.min((page - 1) * size, users.size());
            int to = Math.min(from + size, users.size());
            return new BlacklistPage(users.subList(from, to), page, size, users.size(), version);
        }
    }
}

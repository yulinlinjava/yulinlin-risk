package com.yulinlin.riskapp.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yulinlin.data.core.YulinlinCoreAutoConfig;
import com.yulinlin.data.core.loadbalan.RandomLoadBalance;
import com.yulinlin.data.core.session.RouteSession;
import com.yulinlin.jdbc.session.JdbcSession;
import com.yulinlin.riskapp.persistence.RiskOrmConfiguration;
import com.yulinlin.riskapp.schema.RiskSchemaInitializer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class GlobalRiskServiceTest {
    private static final String PROJECT = "project-a";
    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T12:01:10Z").toEpochMilli());
    private JdbcTemplate db;
    private RouteSession orm;
    private JdbcSession session;
    private GlobalRiskService service;
    private GlobalEventLog eventLog;
    private int nextId;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:global-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new RiskSchemaInitializer(source).initializeDatabase();
        db = new JdbcTemplate(source);
        session = RiskOrmConfiguration.createSession(source);
        orm = RouteSession.builder().build();
        orm.setLoadBalance(new RandomLoadBalance());
        orm.setWrapperFactory(new YulinlinCoreAutoConfig().wrapperFactory());
        orm.registerSession(session);
        db.update("INSERT INTO risk_project(id,name,key_digest,created_at) VALUES(?,?,?,?)", PROJECT, "项目 A", "a".repeat(64), clock.millis());
        db.update("INSERT INTO risk_project(id,name,key_digest,created_at) VALUES(?,?,?,?)", "project-b", "项目 B", "b".repeat(64), clock.millis());
        var settings = new GlobalRiskSettings();
        settings.setMaxActiveUsers(1); // Exercise restoration across interleaved users.
        eventLog = new GlobalEventLog(orm, clock);
        service = new GlobalRiskService(orm, json, clock, settings, eventLog);
        service.start();
    }

    @AfterEach void teardown() {
        service.stop();
        db.execute("DROP ALL OBJECTS");
        session.shutdown();
    }

    @Test void batchIdempotencyIsAtomicAndIsolatedByProject() throws Exception {
        var batch = new GlobalRiskService.Batch("batch-1", List.of(event("u", "DETAIL", null, 200), event("u", "COMMENT", null, 403)));
        assertThat(send(PROJECT, batch)).isEqualTo(new GlobalRiskService.Ack(2, false));
        assertThat(send(PROJECT, batch)).isEqualTo(new GlobalRiskService.Ack(2, true));
        assertThat(send("project-b", batch)).isEqualTo(new GlobalRiskService.Ack(2, false));
        assertThat(report("u").count()).isEqualTo(2);
        assertThat(report("u").failures()).isEqualTo(1);
        assertThat(report("u").denied()).isEqualTo(1);
        assertThat(report("u").actions()).hasSize(2);
        var conflict = new GlobalRiskService.Batch("batch-1", List.of(event("u", "SEARCH", null, 200)));
        assertThatThrownBy(() -> send(PROJECT, conflict)).hasRootCauseMessage("409 CONFLICT \"batchId 已用于不同内容\"");
        assertThat(report("u").count()).isEqualTo(2);
        var duplicateEvent = event("u", "DETAIL", null, 200);
        assertThatThrownBy(() -> service.submit(PROJECT, new GlobalRiskService.Batch("bad", List.of(duplicateEvent, duplicateEvent))))
                .hasMessageContaining("eventId 不能重复");
        var invalidTime = new GlobalRiskService.Event("old", "u", "DETAIL", null, null, "/detail/{id}", "GET",
                clock.millis() - GlobalRiskService.RETENTION - 1, 10, 200);
        assertThatThrownBy(() -> send(PROJECT, new GlobalRiskService.Batch("invalid-time", List.of(event("u", "DETAIL", null, 200), invalidTime))))
                .hasRootCauseMessage("400 BAD_REQUEST \"occurredAt 必须位于最近 24 小时内，未来时钟偏差最多 60 秒\"");
        assertThat(report("u").count()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_batch WHERE batch_id = 'invalid-time'", Long.class)).isZero();
    }

    @Test void preIpVersionReceiptsRemainIdempotentAfterUpgrade() throws Exception {
        String legacyPayload = "{\"batchId\":\"legacy-retry\",\"events\":[{\"eventId\":\"old-1\",\"userId\":\"u\","
                + "\"action\":\"DETAIL\",\"resourceType\":null,\"resourceId\":null,\"route\":\"/detail\","
                + "\"method\":\"GET\",\"occurredAt\":" + clock.millis() + ",\"durationMs\":10,\"status\":200}]}";
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(legacyPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        db.update("INSERT INTO risk_global_batch(project_id,batch_id,payload_digest,accepted,created_at) VALUES(?,?,?,?,?)",
                PROJECT, "legacy-retry", digest, 1, clock.millis());
        var batch = json.readValue(legacyPayload, GlobalRiskService.Batch.class);
        assertThat(json.writeValueAsString(batch)).isEqualTo(legacyPayload);
        assertThat(send(PROJECT, batch)).isEqualTo(new GlobalRiskService.Ack(1, true));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_summary", Long.class)).isZero();
    }

    @Test void summaryUpdatesPreserveUserBucketPairsAndProjectIsolation() throws Exception {
        long now = clock.millis();
        long earlier = now - 2 * GlobalRiskService.SUMMARY_BUCKET;
        var currentFirst = new GlobalRiskService.Event("current-first", "0001", "DETAIL", null, null, "/detail", "GET", now, 10, 200);
        var currentSecond = new GlobalRiskService.Event("current-second", "0002", "DETAIL", null, null, "/detail", "GET", now, 10, 200);
        var earlierFirst = new GlobalRiskService.Event("earlier-first", "0001", "SEARCH", null, null, "/search", "GET", earlier, 10, 200);
        var earlierSecond = new GlobalRiskService.Event("earlier-second", "0002", "SEARCH", null, null, "/search", "GET", earlier, 10, 200);
        var seed = new GlobalRiskService.Batch("seed", List.of(currentFirst, currentSecond, earlierFirst, earlierSecond));
        send(PROJECT, seed);
        send("project-b", seed);
        // Other users/buckets already exist; only these two pairs should be loaded and accumulated.
        var update = new GlobalRiskService.Batch("update", List.of(currentFirst, earlierSecond));
        send(PROJECT, update);
        assertThat(send(PROJECT, update).duplicate()).isTrue();
        for (String user : List.of("0001", "0002")) {
            var report = report(user);
            assertThat(report.count()).isEqualTo(3);
            assertThat(report.trend()).extracting(GlobalRiskService.Trend::count)
                    .containsExactlyElementsOf(user.equals("0001") ? List.of(1L, 2L) : List.of(2L, 1L));
            var other = service.report("project-b", user, earlier - GlobalRiskService.SUMMARY_BUCKET, now + 1);
            assertThat(other.count()).isEqualTo(2);
            assertThat(other.trend()).extracting(GlobalRiskService.Trend::count).containsExactly(1L, 1L);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_summary", Long.class)).isEqualTo(8);
    }

    @Test void ormJsonFieldsRestoreLegacyPayloadsAndKeepTheirStoredShape() throws Exception {
        long now = clock.millis();
        long minute = GlobalWindow.minute(now);
        long bucket = Math.floorDiv(now, GlobalRiskService.SUMMARY_BUCKET) * GlobalRiskService.SUMMARY_BUCKET;
        String legacySummary = """
                {"actions":{"DETAIL":5},"resources":["content:00123"],
                 "evidence":{"REPEATED_DENIALS":{"at":%d,"ruleId":"REPEATED_DENIALS","reason":"旧证据"}},
                 "normalSamples":1,"anomalySamples":0,"otherActions":0,"historyOnly":0,
                 "resourcesTruncated":false,"samplesLimited":false}
                """.formatted(now);
        String legacyWindow = """
                {"minutes":{"%d":{"count":5,"failures":3,"denied":2}},
                 "resources":{"content:00123":%d},"resourceTruncatedUntil":0,"updatedAt":%d}
                """.formatted(minute, minute, now);
        db.update("INSERT INTO risk_global_summary(project_id,user_id,bucket_at,request_count,failures,denied,suspected,details_json) VALUES(?,?,?,?,?,?,?,?)", PROJECT, "0001", bucket,
                5L, 3L, 2L, true, legacySummary);
        db.update("INSERT INTO risk_global_state(project_id,user_id,updated_at,state_json) VALUES(?,?,?,?)", PROJECT, "0001", now, legacyWindow);
        assertThat(report("0001").count()).isEqualTo(5);
        assertThat(report("0001").actions()).containsExactly(new GlobalRiskService.Action("DETAIL", 5));
        assertThat(report("0001").evidence()).containsExactly(new GlobalRiskService.Evidence(now, "REPEATED_DENIALS", "旧证据"));
        assertThat(service.risk(PROJECT, "0001").judgedAt()).isEqualTo(now);

        send(PROJECT, new GlobalRiskService.Batch("after-legacy", List.of(event("0001", "SEARCH", null, 200))));
        assertThat(report("0001").count()).isEqualTo(6);
        assertThat(report("0001").failures()).isEqualTo(3);
        String summaryJson = db.queryForObject("SELECT details_json FROM risk_global_summary WHERE project_id=? AND user_id=?",
                String.class, PROJECT, "0001");
        String stateJson = db.queryForObject("SELECT state_json FROM risk_global_state WHERE project_id=? AND user_id=?",
                String.class, PROJECT, "0001");
        // The previous Jackson reader must still understand JSON written by the ORM.
        var summary = json.readValue(summaryJson, GlobalRiskService.Summary.class);
        assertThat(summary.actions).containsEntry("DETAIL", 5L).containsEntry("SEARCH", 1L);
        assertThat(summary.resources).containsExactly("content:00123");
        assertThat(json.readTree(summaryJson).has("count")).isFalse();
        var window = json.readValue(stateJson, GlobalWindow.class);
        assertThat(window.minutes.get(minute).count).isEqualTo(6);
        assertThat(window.minutes.get(minute).denied).isEqualTo(2);
        assertThat(window.resources).containsEntry("content:00123", minute);
    }

    @Test void crossActionWindowsRecoverAndExpireButSinglePageBurstsDoNotTrigger() throws Exception {
        for (int minute = 0; minute < 3; minute++) {
            sendMany("active", minute % 2 == 0 ? "DETAIL" : "SEARCH", 350, false, 200);
            // Evict the user's committed memory state; the next minute must restore it from H2.
            sendMany("other", "COMMENT", 1, false, 200);
            if (minute < 2) clock.advance(60_000);
        }
        var risk = service.risk(PROJECT, "active");
        assertThat(risk.status()).isEqualTo("SUSPECTED");
        assertThat(risk.reasons()).extracting(GlobalRiskService.Reason::ruleId).contains("SUSTAINED_REQUESTS");
        assertThat(risk.reasons().getFirst().metrics()).containsEntry("count", 1050L).containsEntry("activeMinutes", 3L);
        assertThat(report("active").count()).isEqualTo(1050);
        assertThat(report("active").sampleCount()).isLessThanOrEqualTo(22);
        assertThat(report("active").samplesLimited()).isTrue();
        assertThat(report("active").evidence()).isNotEmpty();
        sendMany("page", "DETAIL", 1200, true, 200);
        assertThat(service.risk(PROJECT, "page").status()).isEqualTo("OBSERVING");
        assertThat(report("page").count()).isEqualTo(1200);
        assertThat(service.risk("project-b", "active").status()).isEqualTo("NO_DATA");
        clock.advance(6 * 60_000L);
        assertThat(service.risk(PROJECT, "active").status()).isEqualTo("NO_DATA");
        assertThat(service.risk(PROJECT, "active").reasons()).isEmpty();
    }

    @Test void lateEventsAreHistoryOnlyAndRetentionHidesExpiredSamples() throws Exception {
        var late = new GlobalRiskService.Event("late", "late-user", "DETAIL", "video", "v", "/videos/{id}", "GET",
                clock.millis() - 2 * 60 * 60_000L, 15, 200);
        var batch = new GlobalRiskService.Batch("late-batch", List.of(late));
        send(PROJECT, batch);
        assertThat(service.risk(PROJECT, "late-user").status()).isEqualTo("NO_DATA");
        assertThat(report("late-user").count()).isEqualTo(1);
        assertThat(report("late-user").historyOnlyCount()).isEqualTo(1);
        assertThat(service.events(PROJECT, "late-user", clock.millis() - GlobalRiskService.RETENTION, clock.millis(), 1, 20).items()).hasSize(1);
        clock.advance(23 * 60 * 60_000L);
        // The deduplication receipt remains valid for 24 hours even after its event ages out.
        assertThat(send(PROJECT, batch).duplicate()).isTrue();
        assertThat(report("late-user").count()).isZero();
        assertThat(service.events(PROJECT, "late-user", clock.millis() - GlobalRiskService.RETENTION, clock.millis(), 1, 20).items()).isEmpty();
        service.cleanExpired();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_sample", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_summary", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_project", Long.class)).isEqualTo(2);
    }

    @Test void acceptedBoundaryEventsAreStoredEvenWhenOnlyWholeBucketsAreReported() throws Exception {
        long cutoff = clock.millis() - GlobalRiskService.RETENTION;
        var boundary = new GlobalRiskService.Event("boundary", "boundary-user", "DETAIL", null, null, "/detail", "GET", cutoff, 10, 200);
        assertThat(send(PROJECT, new GlobalRiskService.Batch("boundary-batch", List.of(boundary))).accepted()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT SUM(request_count) FROM risk_global_summary", Long.class)).isEqualTo(1);
        assertThat(service.events(PROJECT, "boundary-user", cutoff, clock.millis(), 1, 20).items()).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_event", Long.class)).isZero();
        service.cleanExpired();
        assertThat(db.queryForObject("SELECT SUM(request_count) FROM risk_global_summary", Long.class)).isEqualTo(1);
        var report = report("boundary-user");
        assertThat(report.from()).isGreaterThan(cutoff);
        assertThat(report.count()).isZero();
    }

    @Test void configurableUploadDelayUsesOriginalMinutesRatherThanBatchArrival() throws Exception {
        service.stop();
        var settings = new GlobalRiskSettings();
        settings.setDeniedRequests(2);
        service = new GlobalRiskService(orm, json, clock, settings, eventLog);
        service.start();
        long now = clock.millis();
        var allowed = new GlobalRiskService.Event("delayed-allowed", "delayed", "LOGIN", null, null, "/login", "POST", now - 90_000, 10, 401);
        var recent = new GlobalRiskService.Event("delayed-recent", "delayed", "SEARCH", null, null, "/search", "GET", now - 30_000, 10, 401);
        var tooLate = new GlobalRiskService.Event("delayed-history", "delayed", "DETAIL", null, null, "/detail", "GET", now - 120_001, 10, 401);
        send(PROJECT, new GlobalRiskService.Batch("delayed-batch", List.of(allowed, recent, tooLate)));
        var risk = service.risk(PROJECT, "delayed");
        assertThat(risk.status()).isEqualTo("SUSPECTED");
        assertThat(risk.reasons().getFirst().metrics()).containsEntry("count", 2L).containsEntry("activeMinutes", 2L);
        assertThat(report("delayed").count()).isEqualTo(3);
        assertThat(report("delayed").historyOnlyCount()).isEqualTo(1);
    }

    @Test void failedCommitDoesNotPublishStateSamplesOrReceipt() throws Exception {
        sendMany("failure", "LOGIN", 10, false, 401);
        long priorSamples = db.queryForObject("SELECT COUNT(*) FROM risk_global_sample", Long.class);
        clock.advance(60_000);
        db.execute("ALTER TABLE risk_global_summary ADD CONSTRAINT test_global_failure CHECK(request_count < 20)");
        try {
            assertThatThrownBy(() -> sendMany("failure", "SEARCH", 10, false, 401))
                    .hasRootCauseMessage("503 SERVICE_UNAVAILABLE \"上报暂不可用，请保留 batchId 和原内容重试\"");
        } finally {
            db.execute("ALTER TABLE risk_global_summary DROP CONSTRAINT test_global_failure");
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_sample", Long.class)).isEqualTo(priorSamples);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_batch", Long.class)).isEqualTo(1);
        sendMany("failure", "DETAIL", 2, false, 200);
        assertThat(service.risk(PROJECT, "failure").status()).isEqualTo("OBSERVING");
        assertThat(report("failure").count()).isEqualTo(12);
        assertThat(report("failure").denied()).isEqualTo(10);
    }

    @Test void resourceLowerBoundAndGlobalSampleBudgetRemainBounded() throws Exception {
        service.stop();
        var settings = new GlobalRiskSettings();
        settings.setResourceLimit(128);
        settings.setSamplesPerMinute(3);
        settings.setActionLimit(2);
        service = new GlobalRiskService(orm, json, clock, settings, eventLog);
        service.start();
        for (int minute = 0; minute < 3; minute++) {
            sendMany("crawler", "ACTION_" + minute, 100, true, 200);
            if (minute < 2) clock.advance(60_000);
        }
        var risk = service.risk(PROJECT, "crawler");
        assertThat(risk.status()).isEqualTo("SUSPECTED");
        assertThat(risk.dataComplete()).isFalse();
        assertThat(risk.reasons()).extracting(GlobalRiskService.Reason::ruleId).contains("RESOURCE_TRAVERSAL");
        assertThat(risk.reasons().getFirst().metrics()).containsEntry("distinctResources", 128L).containsEntry("distinctIsLowerBound", 1L);
        assertThat(report("crawler").count()).isEqualTo(300);
        assertThat(report("crawler").actionCountsTruncated()).isTrue();
        assertThat(report("crawler").actions()).extracting(GlobalRiskService.Action::action).contains("__OTHER__");
        sendMany("new-user", "DETAIL", 10, false, 200);
        assertThat(db.queryForObject("SELECT sample_count FROM risk_global_sample_budget WHERE minute_at = ?", Integer.class,
                GlobalWindow.minute(clock.millis()))).isEqualTo(3);
        assertThat(report("new-user").count()).isEqualTo(10);
        assertThat(report("new-user").sampleCount()).isZero();
        assertThat(report("new-user").samplesLimited()).isTrue();
    }

    @Test void receiptFailureRollsBackEveryEarlierWriteBeforeTheBatchCanBeRetried() throws Exception {
        send(PROJECT, new GlobalRiskService.Batch("before", List.of(event("0001", "DETAIL", null, 200))));
        long samplesBefore = db.queryForObject("SELECT COUNT(*) FROM risk_global_sample", Long.class);
        long eventsBefore = db.queryForObject("SELECT COUNT(*) FROM risk_global_event", Long.class);
        int budgetBefore = db.queryForObject("SELECT sample_count FROM risk_global_sample_budget WHERE minute_at=?",
                Integer.class, GlobalWindow.minute(clock.millis()));
        String stateBefore = db.queryForObject("SELECT state_json FROM risk_global_state WHERE project_id=? AND user_id=?",
                String.class, PROJECT, "0001");
        var failed = new GlobalRiskService.Batch("fail-receipt", List.of(event("0001", "SEARCH", "00123", 403)));
        db.execute("ALTER TABLE risk_global_batch ADD CONSTRAINT test_receipt_failure CHECK (batch_id <> 'fail-receipt')");
        try {
            assertThatThrownBy(() -> send(PROJECT, failed)).hasRootCauseMessage(
                    "503 SERVICE_UNAVAILABLE \"上报暂不可用，请保留 batchId 和原内容重试\"");
        } finally {
            db.execute("ALTER TABLE risk_global_batch DROP CONSTRAINT test_receipt_failure");
        }
        assertThat(report("0001").count()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_sample", Long.class)).isEqualTo(samplesBefore);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_event", Long.class)).isEqualTo(eventsBefore);
        assertThat(db.queryForObject("SELECT sample_count FROM risk_global_sample_budget WHERE minute_at=?",
                Integer.class, GlobalWindow.minute(clock.millis()))).isEqualTo(budgetBefore);
        assertThat(db.queryForObject("SELECT state_json FROM risk_global_state WHERE project_id=? AND user_id=?",
                String.class, PROJECT, "0001")).isEqualTo(stateBefore);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_batch WHERE batch_id='fail-receipt'", Long.class)).isZero();
        assertThat(send(PROJECT, failed).duplicate()).isFalse();
        assertThat(send(PROJECT, failed).duplicate()).isTrue();
        assertThat(report("0001").count()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_event", Long.class)).isEqualTo(eventsBefore + 1);
        var events = service.events(PROJECT, "0001", clock.millis() - 60_000, clock.millis() + 1, 1, 10).items();
        assertThat(events).extracting(GlobalRiskService.HistoryEvent::userId).containsOnly("0001");
        assertThat(events).extracting(GlobalRiskService.HistoryEvent::resourceId).contains(null, "00123");
    }

    @Test void fullEventLogPersistsEveryEventWithoutSamplingAndPaginatesByStableCursor() throws Exception {
        service.stop();
        var settings = new GlobalRiskSettings();
        settings.setNormalSamples(0);
        settings.setAnomalySamples(0);
        service = new GlobalRiskService(orm, json, clock, settings, eventLog);
        service.start();
        var entries = new ArrayList<GlobalRiskService.Event>();
        for (int i = 0; i < 200; i++) {
            entries.add(new GlobalRiskService.Event("full-" + i, i < 150 ? "u" : "other", "access", null,
                    i % 2 == 0 ? "video:" + i : null, "/videos/" + i, "EVENT", clock.millis(), 0, 200,
                    i % 2 == 0 ? "2001:db8::1" : null));
        }
        var batch = new GlobalRiskService.Batch("full-log", entries);
        assertThat(send(PROJECT, batch).accepted()).isEqualTo(200);
        assertThat(send(PROJECT, batch).duplicate()).isTrue();
        send("project-b", batch);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_sample", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_event WHERE project_id=?", Long.class, PROJECT)).isEqualTo(200);

        var first = service.eventLog(PROJECT, null, null, null, null, 50);
        assertThat(first.items()).hasSize(50);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        var page = first;
        var all = new ArrayList<GlobalEventLog.Entry>();
        do {
            all.addAll(page.items());
            if (!page.hasMore()) break;
            page = service.eventLog(PROJECT, null, first.from(), first.to(), page.nextCursor(), 50);
        } while (true);
        assertThat(all).hasSize(200);
        assertThat(all).extracting(GlobalEventLog.Entry::eventId).doesNotHaveDuplicates();
        assertThat(all).extracting(GlobalEventLog.Entry::eventId)
                .containsExactlyInAnyOrderElementsOf(entries.stream().map(GlobalRiskService.Event::eventId).toList());
        assertThat(page.nextCursor()).isNull();
        assertThat(service.eventLog(PROJECT, "u", null, null, null, 200).items()).hasSize(150);
        assertThat(service.eventLog(PROJECT, "missing", null, null, null, 50).items()).isEmpty();
        var withIp = all.stream().filter(e -> e.eventId().equals("full-0")).findFirst().orElseThrow();
        assertThat(withIp.ip()).isEqualTo("2001:db8::1");
        assertThat(withIp.resourceId()).isEqualTo("video:0");
        assertThat(withIp.route()).isEqualTo("/videos/0");
        assertThat(withIp.occurredAt()).isEqualTo(clock.millis());

        assertThatThrownBy(() -> service.eventLog(PROJECT, null, null, null, "invalid", 50)).hasMessageContaining("游标无效");
        assertThatThrownBy(() -> service.eventLog(PROJECT, null, null, null, "1:0", 50)).hasMessageContaining("游标无效");
        assertThatThrownBy(() -> service.eventLog(PROJECT, null, null, null, "9223372036854775808:1", 50)).hasMessageContaining("游标无效");
        assertThatThrownBy(() -> service.eventLog(PROJECT, null, null, null, null, 201)).hasMessageContaining("pageSize");
    }

    @Test void eventDetailsExpireAtTwelveHoursWhileAggregatesAndReceiptsRemainForTwentyFour() throws Exception {
        long boundary = clock.millis() - GlobalEventLog.RETENTION;
        var older = new GlobalRiskService.Event("older", "retained", "access", null, null, "/older", "EVENT", boundary - 1, 0, 200);
        var edge = new GlobalRiskService.Event("edge", "retained", "access", null, "v", "/edge", "EVENT", boundary, 0, 200, "127.0.0.1");
        var recent = new GlobalRiskService.Event("recent", "retained", "access", null, null, "/recent", "EVENT", clock.millis() - 1000, 0, 200);
        var batch = new GlobalRiskService.Batch("retention", List.of(older, edge, recent));
        send(PROJECT, batch);
        assertThat(service.eventLog(PROJECT, null, null, null, null, 50).items())
                .extracting(GlobalEventLog.Entry::eventId).containsExactly("recent", "edge");
        assertThat(service.events(PROJECT, "retained", clock.millis() - GlobalRiskService.RETENTION + 1, clock.millis() + 1, 1, 50).items())
                .extracting(GlobalRiskService.HistoryEvent::eventId).containsExactly("recent", "edge");
        assertThat(report("retained").count()).isEqualTo(3);

        clock.advance(1);
        assertThat(service.eventLog(PROJECT, null, boundary - 1000, clock.millis(), null, 50).items())
                .extracting(GlobalEventLog.Entry::eventId).containsExactly("recent");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_event", Long.class)).isEqualTo(2);
        eventLog.cleanExpired();
        service.cleanExpired();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_event", Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_sample", Long.class)).isEqualTo(1);
        assertThat(report("retained").count()).isEqualTo(3);
        assertThat(send(PROJECT, batch).duplicate()).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_global_event", Long.class)).isEqualTo(1);
        assertThat(service.eventLog(PROJECT, null, boundary - 1, boundary, null, 50).items()).isEmpty();
    }

    @Test void eventCursorCombinesRemainingSameTimestampRowsWithEarlierTimestamps() throws Exception {
        long now = clock.millis();
        var entries = List.of(
                new GlobalRiskService.Event("new-0", "timeline", "access", null, null, "/n", "EVENT", now, 0, 200),
                new GlobalRiskService.Event("new-1", "timeline", "access", null, null, "/n", "EVENT", now, 0, 200),
                new GlobalRiskService.Event("new-2", "timeline", "access", null, null, "/n", "EVENT", now, 0, 200),
                new GlobalRiskService.Event("middle", "timeline", "access", null, null, "/m", "EVENT", now - 1, 0, 200),
                new GlobalRiskService.Event("old-0", "timeline", "access", null, null, "/o", "EVENT", now - 2, 0, 200),
                new GlobalRiskService.Event("old-1", "timeline", "access", null, null, "/o", "EVENT", now - 2, 0, 200));
        send(PROJECT, new GlobalRiskService.Batch("mixed-time", entries));
        for (String user : new String[] {null, "timeline"}) {
            var first = service.eventLog(PROJECT, user, null, null, null, 2);
            assertThat(first.items()).extracting(GlobalEventLog.Entry::eventId).containsExactly("new-2", "new-1");
            var second = service.eventLog(PROJECT, user, first.from(), first.to(), first.nextCursor(), 2);
            assertThat(second.items()).extracting(GlobalEventLog.Entry::eventId).containsExactly("new-0", "middle");
            assertThat(second.hasMore()).isTrue();
            var third = service.eventLog(PROJECT, user, first.from(), first.to(), second.nextCursor(), 2);
            assertThat(third.items()).extracting(GlobalEventLog.Entry::eventId).containsExactly("old-1", "old-0");
            assertThat(third.hasMore()).isFalse();
            var pastUpperBound = service.eventLog(PROJECT, user, now - 2, now, (now + 1000) + ":1", 50);
            assertThat(pastUpperBound.items()).extracting(GlobalEventLog.Entry::eventId).containsExactly("middle", "old-1", "old-0");
        }
    }

    @Test void expiredEventLogIsDeletedInBoundedBatchesWithoutRemovingCurrentEventsOrProjects() {
        long cutoff = clock.millis() - GlobalEventLog.RETENTION;
        db.update("""
                INSERT INTO risk_global_event(project_id,event_id,user_id,action,route,occurred_at)
                SELECT ?, 'expired-' || x, 'u', 'access', '/old', ? FROM SYSTEM_RANGE(1, 4501)
                """, PROJECT, cutoff - 1);
        db.update("INSERT INTO risk_global_event(project_id,event_id,user_id,action,route,occurred_at) VALUES(?, 'keep', 'u', 'access', '/keep', ?)",
                PROJECT, cutoff);
        assertThat(service.eventLog(PROJECT, null, null, null, null, 50).items()).extracting(GlobalEventLog.Entry::eventId).containsExactly("keep");
        eventLog.cleanExpired();
        assertThat(db.queryForList("SELECT event_id FROM risk_global_event", String.class)).containsExactly("keep");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM risk_project", Long.class)).isEqualTo(2);
    }

    private GlobalRiskService.Report report(String user) {
        return service.report(PROJECT, user, clock.millis() - GlobalRiskService.RETENTION + 1, clock.millis());
    }

    private GlobalRiskService.Ack send(String project, GlobalRiskService.Batch batch) throws Exception {
        return service.submit(project, batch).get(10, TimeUnit.SECONDS);
    }

    private void sendMany(String user, String action, int count, boolean distinctResources, int status) throws Exception {
        for (int sent = 0; sent < count;) {
            var events = new ArrayList<GlobalRiskService.Event>();
            for (int i = 0; i < Math.min(200, count - sent); i++) events.add(event(user, action, distinctResources ? "r-" + nextId : null, status));
            send(PROJECT, new GlobalRiskService.Batch("batch-" + nextId++, events));
            sent += events.size();
        }
    }

    private GlobalRiskService.Event event(String user, String action, String resource, int status) {
        return new GlobalRiskService.Event("event-" + nextId++, user, action, resource == null ? null : "content", resource,
                "/content/{id}", "GET", clock.millis(), 20, status);
    }

    private static final class MutableClock extends Clock {
        private long time;
        MutableClock(long time) { this.time = time; }
        void advance(long amount) { time += amount; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(time); }
        @Override public long millis() { return time; }
    }
}

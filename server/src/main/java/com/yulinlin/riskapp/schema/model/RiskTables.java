package com.yulinlin.riskapp.schema.model;

import com.yulinlin.data.core.anno.JoinField;
import com.yulinlin.data.core.anno.JoinMeta;
import com.yulinlin.data.core.anno.JoinTable;
import com.yulinlin.data.core.anno.TextTypeEnum;
import com.yulinlin.riskapp.global.GlobalRiskService.Summary;
import com.yulinlin.riskapp.global.GlobalWindow;
import com.yulinlin.security.risk.RiskModels.Match;
import java.util.List;

/** Complete ORM models shared by schema generation and runtime persistence. */
public final class RiskTables {
    private RiskTables() { }

    @JoinTable(value = "RISK_ADMIN_ACCOUNT", autoSchema = true)
    public static class AdminAccount {
        @JoinMeta(primaryKey = true) @JoinField(name = "ID") public Integer id;
        @JoinField(name = "USERNAME", textLength = 64) public String username;
        @JoinField(name = "PASSWORD_HASH", textLength = 100) public String passwordHash;
        @JoinField(name = "CREATED_AT") public Long createdAt;
    }

    @JoinTable(value = "RISK_PROJECT", autoSchema = true)
    public static class Project {
        @JoinMeta(primaryKey = true) @JoinField(name = "ID", textLength = 36) public String id;
        @JoinField(name = "NAME", textLength = 100) public String name;
        @JoinField(name = "KEY_DIGEST", textLength = 64) public String keyDigest;
        @JoinField(name = "API_KEY", textLength = 43) public String apiKey;
        @JoinField(name = "CREATED_AT") public Long createdAt;
        @JoinField(name = "BLACKLIST_VERSION") public Long blacklistVersion;
    }

    @JoinTable(value = "RISK_EVENT", autoSchema = true)
    public static class Event {
        @JoinMeta(primaryKey = true) @JoinField(name = "ID") public Long id;
        @JoinField(name = "PROJECT_ID", textLength = 36) public String projectId;
        @JoinField(name = "EVENT_ID", textLength = 128) public String eventId;
        @JoinField(name = "USER_ID", textLength = 128) public String userId;
        @JoinField(name = "BEHAVIOR_TYPE", textLength = 64) public String behaviorType;
        @JoinField(name = "RESOURCE_ID", textLength = 256) public String resourceId;
        @JoinField(name = "OCCURRED_AT") public Long occurredAt;
        @JoinField(name = "RECEIVED_AT") public Long receivedAt;
        @JoinField(name = "VERDICT_STATUS", textLength = 24) public String verdictStatus;
        @JoinField(name = "MATCHES_JSON", textType = TextTypeEnum.text) public List<Match> matches;
    }

    @JoinTable(value = "RISK_BLACKLIST", autoSchema = true)
    public static class Blacklist {
        @JoinField(name = "PROJECT_ID", textLength = 36) public String projectId;
        @JoinField(name = "USER_ID", textLength = 128) public String userId;
        @JoinField(name = "REASON", textLength = 500) public String reason;
        @JoinField(name = "ADDED_AT") public Long addedAt;
    }

    @JoinTable(value = "RISK_GLOBAL_BATCH", autoSchema = true)
    public static class GlobalBatch {
        @JoinField(name = "PROJECT_ID", textLength = 36) public String projectId;
        @JoinField(name = "BATCH_ID", textLength = 128) public String batchId;
        @JoinField(name = "PAYLOAD_DIGEST", textLength = 64) public String payloadDigest;
        @JoinField(name = "ACCEPTED") public Integer accepted;
        @JoinField(name = "CREATED_AT") public Long createdAt;
    }

    @JoinTable(value = "RISK_GLOBAL_SUMMARY", autoSchema = true)
    public static class GlobalSummary {
        @JoinField(name = "PROJECT_ID", textLength = 36) public String projectId;
        @JoinField(name = "USER_ID", textLength = 128) public String userId;
        @JoinField(name = "BUCKET_AT") public Long bucketAt;
        @JoinField(name = "REQUEST_COUNT") public Long requestCount;
        @JoinField(name = "FAILURES") public Long failures;
        @JoinField(name = "DENIED") public Long denied;
        @JoinField(name = "SUSPECTED") public Boolean suspected;
        @JoinField(name = "DETAILS_JSON", textType = TextTypeEnum.text) public Summary details;
    }

    @JoinTable(value = "RISK_GLOBAL_STATE", autoSchema = true)
    public static class GlobalState {
        @JoinField(name = "PROJECT_ID", textLength = 36) public String projectId;
        @JoinField(name = "USER_ID", textLength = 128) public String userId;
        @JoinField(name = "UPDATED_AT") public Long updatedAt;
        @JoinField(name = "STATE_JSON", textType = TextTypeEnum.text) public GlobalWindow state;
    }

    @JoinTable(value = "RISK_GLOBAL_EVENT", autoSchema = true)
    public static class GlobalEvent {
        @JoinMeta(primaryKey = true) @JoinField(name = "ID") public Long id;
        @JoinField(name = "PROJECT_ID", textLength = 36) public String projectId;
        @JoinField(name = "EVENT_ID", textLength = 128) public String eventId;
        @JoinField(name = "USER_ID", textLength = 128) public String userId;
        @JoinField(name = "ACTION", textLength = 64) public String action;
        @JoinField(name = "RESOURCE_ID", textLength = 256) public String resourceId;
        @JoinField(name = "IP", textLength = 45) public String ip;
        @JoinField(name = "ROUTE", textLength = 256) public String route;
        @JoinField(name = "OCCURRED_AT") public Long occurredAt;
    }

    @JoinTable(value = "RISK_GLOBAL_SAMPLE", autoSchema = true)
    public static class GlobalSample {
        @JoinMeta(primaryKey = true) @JoinField(name = "ID") public Long id;
        @JoinField(name = "PROJECT_ID", textLength = 36) public String projectId;
        @JoinField(name = "USER_ID", textLength = 128) public String userId;
        @JoinField(name = "EVENT_ID", textLength = 128) public String eventId;
        @JoinField(name = "ACTION", textLength = 64) public String action;
        @JoinField(name = "RESOURCE_TYPE", textLength = 64) public String resourceType;
        @JoinField(name = "RESOURCE_ID", textLength = 256) public String resourceId;
        @JoinField(name = "ROUTE", textLength = 256) public String route;
        @JoinField(name = "IP", textLength = 45) public String ip;
        @JoinField(name = "METHOD", textLength = 16) public String method;
        @JoinField(name = "OCCURRED_AT") public Long occurredAt;
        @JoinField(name = "DURATION_MS") public Long durationMs;
        @JoinField(name = "RESPONSE_STATUS") public Integer responseStatus;
        @JoinField(name = "SAMPLE_KIND", textLength = 16) public String sampleKind;
    }

    @JoinTable(value = "RISK_GLOBAL_SAMPLE_BUDGET", autoSchema = true)
    public static class GlobalSampleBudget {
        @JoinMeta(primaryKey = true) @JoinField(name = "MINUTE_AT") public Long minuteAt;
        @JoinField(name = "SAMPLE_COUNT") public Integer sampleCount;
    }
}

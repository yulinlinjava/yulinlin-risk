package com.yulinlin.risk.sdk;

import java.util.UUID;

/** An observed behavior. Timestamps are epoch milliseconds from the business server. */
public record RiskEvent(String eventId, String userId, String action, String resourceType,
                        String resourceId, String route, String method, long occurredAt,
                        long durationMs, int status, String ip) {
    /** Keeps existing explicit events source-compatible; earlier callers did not supply an IP. */
    public RiskEvent(String eventId, String userId, String action, String resourceType,
                     String resourceId, String route, String method, long occurredAt,
                     long durationMs, int status) {
        this(eventId, userId, action, resourceType, resourceId, route, method, occurredAt, durationMs, status, null);
    }

    /** Creates metadata once; HTTP fields are compatibility placeholders, not measured request results. */
    public RiskEvent(String userId, String action, String resourceId) {
        this(UUID.randomUUID().toString(), userId, action, null, resourceId,
                "/behavior", "EVENT", System.currentTimeMillis(), 0, 200);
    }

    boolean valid() {
        return text(eventId, 128) && text(userId, 128) && text(action, 64)
                && !"__OTHER__".equals(action)
                && optional(resourceType, 64) && optional(resourceId, 256) && optional(ip, 45)
                && text(route, 256) && text(method, 16) && occurredAt > 0
                && durationMs >= 0 && durationMs <= 86_400_000L && status >= 100 && status <= 599;
    }

    private static boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit && value.indexOf('\0') < 0;
    }

    private static boolean optional(String value, int limit) {
        return value == null || text(value, limit);
    }
}

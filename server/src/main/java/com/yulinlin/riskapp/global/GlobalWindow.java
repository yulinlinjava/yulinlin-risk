package com.yulinlin.riskapp.global;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** A persisted, bounded set of minute buckets. Only the writer mutates a copy. */
public final class GlobalWindow {
    static final long MINUTE = 60_000L;
    public TreeMap<Long, Counts> minutes = new TreeMap<>();
    public LinkedHashMap<String, Long> resources = new LinkedHashMap<>();
    public long resourceTruncatedUntil;
    public long updatedAt;

    public static final class Counts {
        public long count;
        public long failures;
        public long denied;
        Counts copy() {
            var result = new Counts();
            result.count = count; result.failures = failures; result.denied = denied;
            return result;
        }
    }

    GlobalWindow copy() {
        var result = new GlobalWindow();
        minutes.forEach((at, value) -> result.minutes.put(at, value.copy()));
        result.resources.putAll(resources);
        result.resourceTruncatedUntil = resourceTruncatedUntil;
        result.updatedAt = updatedAt;
        return result;
    }

    void add(GlobalRiskService.Event event, long now, int resourceLimit) {
        prune(now);
        long minute = minute(event.occurredAt());
        var counts = minutes.computeIfAbsent(minute, ignored -> new Counts());
        counts.count++;
        if (event.status() >= 400) counts.failures++;
        if (denied(event.status())) counts.denied++;
        if (event.resourceId() != null) {
            String resource = (event.resourceType() == null ? "" : event.resourceType()) + '\0' + event.resourceId();
            if (resources.containsKey(resource)) {
                resources.merge(resource, minute, Math::max);
            } else if (resources.size() < resourceLimit) {
                resources.put(resource, minute);
            } else {
                // Distinct count is a lower bound; reaching a threshold is still valid evidence.
                resourceTruncatedUntil = Math.max(resourceTruncatedUntil, minute + 5 * MINUTE);
            }
        }
        updatedAt = now;
    }

    void prune(long now) {
        long start = minute(now) - 4 * MINUTE;
        minutes.headMap(start, false).clear();
        resources.entrySet().removeIf(entry -> entry.getValue() < start);
    }

    GlobalRiskService.Risk evaluate(long now, GlobalRiskSettings settings) {
        long end = minute(now);
        long start = end - 4 * MINUTE;
        long count = 0, failures = 0, denied = 0;
        int activeMinutes = 0;
        for (var entry : minutes.subMap(start, true, end, true).entrySet()) {
            count += entry.getValue().count;
            failures += entry.getValue().failures;
            denied += entry.getValue().denied;
            if (entry.getValue().count > 0) activeMinutes++;
        }
        long distinct = resources.values().stream().filter(at -> at >= start && at <= end).count();
        boolean complete = now >= resourceTruncatedUntil;
        if (count == 0) return new GlobalRiskService.Risk("NO_DATA", java.util.List.of(), null, complete);
        Map<String, Long> metrics = Map.of("count", count, "failures", failures, "denied", denied,
                "activeMinutes", (long) activeMinutes, "distinctResources", distinct,
                "windowFrom", start, "windowTo", end + MINUTE,
                "distinctIsLowerBound", complete ? 0L : 1L);
        var reasons = new ArrayList<GlobalRiskService.Reason>();
        if (count >= settings.getSustainedRequests() && activeMinutes >= 3) {
            reasons.add(new GlobalRiskService.Reason("SUSTAINED_REQUESTS",
                    "跨接口请求 " + count + " 次，覆盖 " + activeMinutes + " 个活跃分钟", metrics));
        }
        if (count >= settings.getResourceRequests() && distinct >= settings.getDistinctResources() && activeMinutes >= 3) {
            reasons.add(new GlobalRiskService.Reason("RESOURCE_TRAVERSAL",
                    "跨接口请求 " + count + " 次，访问至少 " + distinct + " 个不同资源", metrics));
        }
        if (denied >= settings.getDeniedRequests() && denied / (double) count >= settings.getDeniedRatio() && activeMinutes >= 2) {
            reasons.add(new GlobalRiskService.Reason("REPEATED_DENIALS",
                    "401/403/429 响应 " + denied + " 次，占请求 " + Math.round(denied * 100.0 / count) + "%", metrics));
        }
        return new GlobalRiskService.Risk(reasons.isEmpty() ? activeMinutes < 3 ? "OBSERVING" : "NOT_MATCHED" : "SUSPECTED",
                java.util.List.copyOf(reasons), updatedAt, complete);
    }

    static boolean denied(int status) { return status == 401 || status == 403 || status == 429; }
    static long minute(long time) { return Math.floorDiv(time, MINUTE) * MINUTE; }
}

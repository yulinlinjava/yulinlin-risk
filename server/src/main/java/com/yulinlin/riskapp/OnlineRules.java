package com.yulinlin.riskapp;

import com.yulinlin.riskapp.EventStore.SubjectKey;
import com.yulinlin.security.risk.ActiveAccessDetector;
import com.yulinlin.security.risk.FastSwitchDetector;
import com.yulinlin.security.risk.RiskModels.BehaviorEvent;
import com.yulinlin.security.risk.RiskModels.Detection;
import com.yulinlin.security.risk.RiskModels.DetectionStatus;
import com.yulinlin.security.risk.RiskModels.Match;
import com.yulinlin.security.risk.RiskModels.Subject;
import com.yulinlin.security.risk.RiskProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Owned by the single event writer. The database remains the source for recovery. */
@Component
class OnlineRules {
    record Verdict(String status, List<Match> matches) {}

    private static final long BUCKET = Duration.ofMinutes(10).toMillis();
    private final LinkedHashMap<SubjectKey, State> states = new LinkedHashMap<>(16, .75f, true);
    private final EventStore store;
    private final FastSwitchDetector fast;
    private final ActiveAccessDetector active;
    private final long activeWindow;
    private final long idleMillis;
    private final int maxSubjects;
    private final int recoveryLimit;

    OnlineRules(EventStore store, RiskProperties rules, @Value("${risk.max-subjects}") int maxSubjects,
                @Value("${risk.recovery-limit}") int recoveryLimit) {
        if (maxSubjects < 1 || recoveryLimit < 1 || recoveryLimit > 100_000) {
            throw new IllegalArgumentException("风控内存或恢复容量无效");
        }
        this.store = store;
        this.fast = new FastSwitchDetector(rules);
        this.active = new ActiveAccessDetector(rules);
        this.activeWindow = rules.getBehaviors().get(Inputs.BEHAVIOR).getActiveAccess().getWindow().toMillis();
        this.idleMillis = rules.getMemory().getRetention().toMillis();
        this.maxSubjects = maxSubjects;
        this.recoveryLimit = recoveryLimit;
    }

    boolean cold(SubjectKey key, long now) {
        var state = states.get(key);
        return state == null || now - state.lastSeen >= idleMillis;
    }

    long recoveryCutoff(long now) {
        return windowStart(now) - idleMillis;
    }

    Verdict check(SubjectKey key, String resourceId, long now, boolean recoverable) {
        var state = states.get(key);
        if (state != null && now - state.lastSeen >= idleMillis) {
            invalidate(key);
            state = null;
        }
        if (state == null) {
            state = new State(new Subject(Projects.digest(key.projectId() + '\0' + key.userId()), key.behaviorType()));
            // Register before recovery so a failed batch can always invalidate any mutated state.
            states.put(key, state);
            if (recoverable) recover(key, state, now);
        }
        var results = detect(state, resourceId, now);
        if (now < state.incompleteUntil) return new Verdict("INCOMPLETE", List.of());
        var matches = results.stream().flatMap(result -> result.matches().stream()).toList();
        if (!matches.isEmpty()) return new Verdict("SUSPECTED", matches);
        if (hasStatus(results, DetectionStatus.CAPACITY)) return new Verdict("INCOMPLETE", List.of());
        if (hasStatus(results, DetectionStatus.OBSERVING)) return new Verdict("OBSERVING", List.of());
        return new Verdict("NOT_MATCHED", List.of());
    }

    void invalidate(SubjectKey key) {
        var state = states.remove(key);
        if (state != null) reset(state.subject);
    }

    void trim() {
        while (states.size() > maxSubjects) reset(states.pollFirstEntry().getValue().subject);
    }

    private void recover(SubjectKey key, State state, long now) {
        var history = store.recover(key, windowStart(now), idleMillis, recoveryLimit);
        if (history.size() > recoveryLimit) {
            state.incompleteUntil = now + activeWindow + BUCKET;
            return;
        }
        if (history.isEmpty() || now - history.getLast().receivedAt() >= idleMillis) return;
        for (var row : history) {
            if (state.lastSeen >= 0 && row.receivedAt() - state.lastSeen >= idleMillis) reset(state.subject);
            detect(state, row.resourceId(), row.receivedAt());
        }
    }

    private long windowStart(long now) {
        return Math.floorDiv(now, BUCKET) * BUCKET - activeWindow;
    }

    private List<Detection> detect(State state, String resourceId, long now) {
        state.lastSeen = Math.max(now, state.lastSeen);
        var event = new BehaviorEvent(state.subject.userId(), state.subject.behaviorType(), resourceId);
        return List.of(fast.detect(event, state.lastSeen), active.detect(event, state.lastSeen));
    }

    private void reset(Subject subject) {
        fast.reset(subject);
        active.reset(subject);
    }

    private static boolean hasStatus(List<Detection> results, DetectionStatus status) {
        return results.stream().anyMatch(result -> result.status() == status);
    }

    private static final class State {
        final Subject subject;
        long lastSeen = -1;
        long incompleteUntil;

        State(Subject subject) {
            this.subject = subject;
        }
    }
}

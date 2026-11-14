package com.yulinlin.riskapp;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class Retention {
    private final EventStore store;
    private final Clock clock;

    Retention(EventStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 5_000)
    void clean() {
        long cutoff = clock.millis() - Inputs.RETENTION;
        for (int i = 0; i < 8 && store.deleteExpired(cutoff, 2_000) == 2_000; i++) {
            // Each batch commits independently so the writer can proceed between batches.
        }
    }
}

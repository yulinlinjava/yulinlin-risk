package com.yulinlin.risk.sdk;

import java.util.List;

public record RiskBatch(String batchId, List<RiskEvent> events) {
    public RiskBatch {
        events = List.copyOf(events);
    }
}

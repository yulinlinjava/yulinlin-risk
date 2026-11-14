package com.yulinlin.risk.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;

/** Transport extension point; implementations must honor interruption and bounded request timeouts. */
public interface RiskTransport {
    record SendResult(int status, int accepted) {}
    record BlacklistPage(List<String> userIds, int page, int pageSize, long total, long version) {
        public BlacklistPage { userIds = List.copyOf(userIds); }
    }

    SendResult send(RiskBatch batch) throws IOException, InterruptedException;
    BlacklistPage blacklist(int page, int pageSize) throws IOException, InterruptedException;
    JsonNode risk(String userId) throws IOException, InterruptedException;
}

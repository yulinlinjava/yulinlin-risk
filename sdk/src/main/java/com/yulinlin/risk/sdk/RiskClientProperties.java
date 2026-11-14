package com.yulinlin.risk.sdk;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("yulinlin.risk")
public class RiskClientProperties {
    private boolean enabled;
    private URI baseUrl;
    private String apiKey;
    private int batchSize = 100;
    private int queueCapacity = 4096;
    private Duration flushInterval = Duration.ofSeconds(1);
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(5);
    private int maxRetries = 3;
    private Duration retryDelay = Duration.ofMillis(500);
    private Duration blacklistRefreshInterval = Duration.ofSeconds(60);
    private int blacklistMaxUsers = 200_000;
    private Duration shutdownTimeout = Duration.ofSeconds(5);

    public void validate() {
        if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("yulinlin.risk.base-url must be an HTTP(S) URL without credentials, query or fragment");
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 512
                || apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("yulinlin.risk.api-key must be configured");
        }
        if (batchSize < 1 || batchSize > 200 || queueCapacity < batchSize || queueCapacity > 1_000_000
                || maxRetries < 0 || maxRetries > 10 || blacklistMaxUsers < 1 || blacklistMaxUsers > 1_000_000) {
            throw new IllegalArgumentException("Invalid risk SDK queue, batch, retry or blacklist capacity");
        }
        duration(flushInterval, 1, 30_000, "flush-interval");
        duration(connectTimeout, 1, 60_000, "connect-timeout");
        duration(requestTimeout, 1, 60_000, "request-timeout");
        duration(retryDelay, 1, 30_000, "retry-delay");
        duration(blacklistRefreshInterval, 1_000, 3_600_000, "blacklist-refresh-interval");
        duration(shutdownTimeout, 1, 60_000, "shutdown-timeout");
    }

    private static void duration(Duration value, long min, long max, String field) {
        if (value == null || value.isNegative() || value.compareTo(Duration.ofMillis(min)) < 0
                || value.compareTo(Duration.ofMillis(max)) > 0) throw new IllegalArgumentException("Invalid " + field);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public Duration getFlushInterval() { return flushInterval; }
    public void setFlushInterval(Duration value) { flushInterval = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { requestTimeout = value; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int value) { maxRetries = value; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration value) { retryDelay = value; }
    public Duration getBlacklistRefreshInterval() { return blacklistRefreshInterval; }
    public void setBlacklistRefreshInterval(Duration value) { blacklistRefreshInterval = value; }
    public int getBlacklistMaxUsers() { return blacklistMaxUsers; }
    public void setBlacklistMaxUsers(int value) { blacklistMaxUsers = value; }
    public Duration getShutdownTimeout() { return shutdownTimeout; }
    public void setShutdownTimeout(Duration value) { shutdownTimeout = value; }
}

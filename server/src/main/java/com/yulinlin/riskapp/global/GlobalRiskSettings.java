package com.yulinlin.riskapp.global;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Limits keep both the ingestion queue and each user's statistics bounded. */
@Component
@ConfigurationProperties(prefix = "risk.global")
public class GlobalRiskSettings {
    private int queueCapacity = 256;
    private int maxActiveUsers = 10000;
    private int actionLimit = 32;
    private int resourceLimit = 512;
    private int normalSamples = 2;
    private int anomalySamples = 20;
    private int samplesPerMinute = 2000;
    private int analyticsConcurrency = 2;
    private long sustainedRequests = 1000;
    private long resourceRequests = 300;
    private int distinctResources = 100;
    private long deniedRequests = 20;
    private double deniedRatio = .5;
    private Duration onlineMaxDelay = Duration.ofMinutes(2);

    public void validate() {
        if (queueCapacity < 1 || queueCapacity > 4096 || maxActiveUsers < 1 || maxActiveUsers > 100000
                || actionLimit < 1 || actionLimit > 128 || resourceLimit < 1 || resourceLimit > 2048
                || normalSamples < 0 || normalSamples > 20 || anomalySamples < 0 || anomalySamples > 100
                || samplesPerMinute < 1 || samplesPerMinute > 100000 || analyticsConcurrency < 1
                || analyticsConcurrency > 16 || sustainedRequests < 1 || resourceRequests < 1
                || distinctResources < 1 || distinctResources > resourceLimit || deniedRequests < 1
                || onlineMaxDelay == null || onlineMaxDelay.isNegative() || onlineMaxDelay.compareTo(Duration.ofMinutes(5)) > 0
                || !Double.isFinite(deniedRatio) || deniedRatio <= 0 || deniedRatio > 1) {
            throw new IllegalArgumentException("全局风控容量或阈值配置无效");
        }
    }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public int getMaxActiveUsers() { return maxActiveUsers; }
    public void setMaxActiveUsers(int value) { maxActiveUsers = value; }
    public int getActionLimit() { return actionLimit; }
    public void setActionLimit(int value) { actionLimit = value; }
    public int getResourceLimit() { return resourceLimit; }
    public void setResourceLimit(int value) { resourceLimit = value; }
    public int getNormalSamples() { return normalSamples; }
    public void setNormalSamples(int value) { normalSamples = value; }
    public int getAnomalySamples() { return anomalySamples; }
    public void setAnomalySamples(int value) { anomalySamples = value; }
    public int getSamplesPerMinute() { return samplesPerMinute; }
    public void setSamplesPerMinute(int value) { samplesPerMinute = value; }
    public int getAnalyticsConcurrency() { return analyticsConcurrency; }
    public void setAnalyticsConcurrency(int value) { analyticsConcurrency = value; }
    public long getSustainedRequests() { return sustainedRequests; }
    public void setSustainedRequests(long value) { sustainedRequests = value; }
    public long getResourceRequests() { return resourceRequests; }
    public void setResourceRequests(long value) { resourceRequests = value; }
    public int getDistinctResources() { return distinctResources; }
    public void setDistinctResources(int value) { distinctResources = value; }
    public long getDeniedRequests() { return deniedRequests; }
    public void setDeniedRequests(long value) { deniedRequests = value; }
    public double getDeniedRatio() { return deniedRatio; }
    public void setDeniedRatio(double value) { deniedRatio = value; }
    public Duration getOnlineMaxDelay() { return onlineMaxDelay; }
    public void setOnlineMaxDelay(Duration value) { onlineMaxDelay = value; }
}

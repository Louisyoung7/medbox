package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 洞察层配置（medbox.ai.insight.*，见 05 第 2 章）。 */
@ConfigurationProperties(prefix = "medbox.ai.insight")
public class AiInsightProperties {
    private boolean enabled = true;
    private long timeoutMs = 15_000L;
    private long streamTimeoutMs = 60_000L;
    private int maxRecords = 200;
    private String timezone = "Asia/Shanghai";
    private String aesKey;
    private long scheduleScanIntervalMs = 3_600_000L;
    
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public long getStreamTimeoutMs() { return streamTimeoutMs; }
    public void setStreamTimeoutMs(long streamTimeoutMs) { this.streamTimeoutMs = streamTimeoutMs; }

    public int getMaxRecords() { return maxRecords; }
    public void setMaxRecords(int maxRecords) { this.maxRecords = maxRecords; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getAesKey() { return aesKey; }
    public void setAesKey(String aesKey) { this.aesKey = aesKey; }

    public long getScheduleScanIntervalMs() { return scheduleScanIntervalMs; }
    public void setScheduleScanIntervalMs(long scheduleScanIntervalMs) { this.scheduleScanIntervalMs = scheduleScanIntervalMs; }
}
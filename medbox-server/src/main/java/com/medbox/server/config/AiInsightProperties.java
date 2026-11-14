package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 洞察层配置（medbox.ai.insight.*，见 05 第 2 章）。 */
@ConfigurationProperties(prefix = "medbox.ai.insight")
public class AiInsightProperties {
    private boolean enabled = true;
    private long timeoutMs = 15_000L;
    private long streamTimeoutMs = 60_000L;
    private int maxRecords = 200;
    /** 用药安全洞察最多读取的药品档案条数。 */
    private int maxMedicines = 50;
    /** 单条药品说明送入模型的最大字符数（超出截断，控制 prompt 体积）。 */
    private int maxDescriptionChars = 300;
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

    public int getMaxMedicines() { return maxMedicines; }
    public void setMaxMedicines(int maxMedicines) { this.maxMedicines = maxMedicines; }

    public int getMaxDescriptionChars() { return maxDescriptionChars; }
    public void setMaxDescriptionChars(int maxDescriptionChars) { this.maxDescriptionChars = maxDescriptionChars; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getAesKey() { return aesKey; }
    public void setAesKey(String aesKey) { this.aesKey = aesKey; }

    public long getScheduleScanIntervalMs() { return scheduleScanIntervalMs; }
    public void setScheduleScanIntervalMs(long scheduleScanIntervalMs) { this.scheduleScanIntervalMs = scheduleScanIntervalMs; }
}
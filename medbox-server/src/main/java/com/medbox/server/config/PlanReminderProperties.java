package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服药提醒扫描配置（{@code medbox.plan-reminder.*}，见文档 06 的 2.6 / 02 第 8 章）。
 *
 * <p>与 {@code medbox.alarm.miss.*} 同一风格：可改的都进配置，避免把"扫描间隔""提前期"这类
 * 常量散落在 Service 里。逃生开关 {@code medbox.plan-reminder.enabled} 默认 true；
 * CI / 排障可置 false 关掉定时扫描。
 */
@ConfigurationProperties(prefix = "medbox.plan-reminder")
public class PlanReminderProperties {

    /** 是否启用提醒定时扫描；false 时整个扫描 Bean 不装配。 */
    private boolean enabled = true;

    /** 扫描间隔（毫秒），默认 60 秒扫一次。 */
    private long scanIntervalMs = 60_000L;

    /**
     * 提前量（分钟）：在计划服药时间前多久推送提醒。默认 10 分钟。
     * 扫描窗口为 {@code [now, now + leadTimeMinutes]} 内的"未来 slot"。
     */
    private long leadTimeMinutes = 10L;

    /**
     * 设备离线时是否跳过提醒：默认 false（与漏服相反）。
     * 提醒对监护端有价值（云端兜底），即使设备离线也照常推；置 true 则与漏服同口径。
     */
    private boolean skipWhenDeviceOffline = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getScanIntervalMs() {
        return scanIntervalMs;
    }

    public void setScanIntervalMs(long scanIntervalMs) {
        this.scanIntervalMs = scanIntervalMs;
    }

    public long getLeadTimeMinutes() {
        return leadTimeMinutes;
    }

    public void setLeadTimeMinutes(long leadTimeMinutes) {
        this.leadTimeMinutes = leadTimeMinutes;
    }

    public boolean isSkipWhenDeviceOffline() {
        return skipWhenDeviceOffline;
    }

    public void setSkipWhenDeviceOffline(boolean skipWhenDeviceOffline) {
        this.skipWhenDeviceOffline = skipWhenDeviceOffline;
    }
}

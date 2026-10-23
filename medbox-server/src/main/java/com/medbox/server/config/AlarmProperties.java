package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 漏服告警扫描配置（{@code medbox.alarm.miss.*}，见文档 06 的 2.9 / 02 第 8 章）。
 *
 * <p>与 {@code medbox.device.*} / {@code medbox.ws.*} 同一风格：可改的都进配置，
 * 避免把"扫描间隔""回看窗口"这类常量散落在 Service 里。逃生开关
 * {@code medbox.alarm.miss.enabled} 默认 true；CI / 排障可置 false 关掉定时扫描。
 */
@ConfigurationProperties(prefix = "medbox.alarm.miss")
public class AlarmProperties {

    /** 是否启用漏服定时扫描；false 时整个扫描 Bean 不装配。 */
    private boolean enabled = true;

    /** 扫描间隔（毫秒），默认 60 秒扫一次。 */
    private long scanIntervalMs = 60_000L;

    /**
     * 回看窗口（分钟）：只判定 {@code [now - lookback, now - tolerance]} 内的槽。
     * 默认 1440（24 小时），防服务重启后漏判，又不至于扫太久的历史。
     */
    private long lookbackMinutes = 1_440L;

    /**
     * 设备离线时跳过判定：设备 {@code online_state != ONLINE} 时该老人的槽不判，
     * 避免"上报不了 = 漏服"误判。默认 true（文档 01 §5.1 用户确认）。
     */
    private boolean skipWhenDeviceOffline = true;

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

    public long getLookbackMinutes() {
        return lookbackMinutes;
    }

    public void setLookbackMinutes(long lookbackMinutes) {
        this.lookbackMinutes = lookbackMinutes;
    }

    public boolean isSkipWhenDeviceOffline() {
        return skipWhenDeviceOffline;
    }

    public void setSkipWhenDeviceOffline(boolean skipWhenDeviceOffline) {
        this.skipWhenDeviceOffline = skipWhenDeviceOffline;
    }
}

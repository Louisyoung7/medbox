package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 主控离线 {@code DEVICE_OFFLINE} 告警配置（{@code medbox.alarm.device-offline.*}，
 * feat/alarm-device-offline，见文档 02 第 8 章 / 06 §2.9）。
 *
 * <p>与 {@code medbox.alarm.device-fault.*} 同一风格：逃生开关 + 去重窗口。
 */
@ConfigurationProperties(prefix = "medbox.alarm.device-offline")
public class DeviceOfflineAlarmProperties {

    /** 是否启用 DEVICE_OFFLINE 告警；false 时只走设备状态跃变（事件流水 + DEVICE_STATUS 推送），不生成告警、不推 ALARM。 */
    private boolean enabled = true;

    /** 同设备离线告警的去重窗口（分钟）：窗口内仅告警一次，重新上线后再离线可再告警。默认 30。 */
    private long dedupWindowMinutes = 30L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDedupWindowMinutes() {
        return dedupWindowMinutes;
    }

    public void setDedupWindowMinutes(long dedupWindowMinutes) {
        this.dedupWindowMinutes = dedupWindowMinutes;
    }
}

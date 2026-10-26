package com.medbox.server.config;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 设备侧异常 DEVICE_FAULT 告警配置（{@code medbox.alarm.device-fault.*}，feat/device-fault，见 06 §2.9）。
 * 与 {@code medbox.alarm.env.*} 同一风格：逃生开关 + 去重窗口。
 */
@ConfigurationProperties(prefix="medbox.alarm.device-fault")
public class DeviceFaultAlarmProperties {
    /** 是否启用 DEVICE_FAULT 告警；false 时只记事件流水，不生成告警、不推 WS。 */
    private boolean enabled = true;

    /** 同设备同故障码的去重窗口（分钟）：窗口内仅告警一次，窗口过后再次上报可再告警。默认 30。 */
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
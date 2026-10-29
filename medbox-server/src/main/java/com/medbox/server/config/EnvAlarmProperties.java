package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 环境超标判定与 ENV 告警配置（{@code medbox.alarm.env.*}，见文档 06 的 2.9 / 01 的 5.4）。
 *
 * <p>与 {@code medbox.alarm.miss.*} 同一风格：可改的都进配置。
 * 逃生开关 {@code medbox.alarm.env.enabled} 默认 true；CI / 排障可置 false 关掉整个环境判定。
 * 去重窗口 {@code dedup-window-minutes} 控制"同一传感器同一指标多久内只告警一次"。
 */
@ConfigurationProperties(prefix = "medbox.alarm.env")
public class EnvAlarmProperties {

    /** 是否启用环境超标判定；false 时整个判定（含 WS 推送）不跑。 */
    private boolean enabled = true;

    /**
     * 同传感器同指标的去重窗口（分钟）：窗口内只落库一条 ENV 告警，数值回落即停止告警，
     * 窗口过后再次越界可再告警。默认 30 分钟。
     */
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

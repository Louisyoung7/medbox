package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 设备注册与事件流水的配置（{@code medbox.device.*}，见文档 02 第 4 章 / 06 的 2.16）。
 *
 * <p>与 {@code medbox.mqtt.*} 的结构化风格保持一致：能改的都放进配置，
 * 避免把"每页几条""默认时区"这类常量散落在 Service 里。
 */
@ConfigurationProperties(prefix = "medbox.device")
public class DeviceProperties {

    /**
     * 产品 key 的默认值，用于注册请求没填 {@code productKey} 时兜底。
     *
     * <p>它是 Topic 的第 2 层（{@code medbox/{productKey}/{deviceId}/...}），
     * 因此**不能留空** —— 留空会让下行订阅主题拼不出来。
     */
    private String defaultProductKey = "medbox-box";

    /** 设备本地时区的默认值（服药时间按此换算，见文档 06 的 2.6.1）。 */
    private String defaultTimezone = "Asia/Shanghai";

    /** 事件流水的默认每页条数。 */
    private int eventPageSize = 20;

    /** 事件流水的每页上限：防深分页拖垮数据库，也防客户端一次拉全量。 */
    private int eventMaxPageSize = 100;

    /**
     * 传感器子设备离线判定阈值（毫秒）：连续这么久没出现在主控的
     * {@code up/sensor/heartbeat} 汇总里即判离线（文档 03 第 3 章：60s × 3 个周期 = 180s）。
     * 子设备超时<b>不生成 DEVICE_OFFLINE 告警</b>，只更新 {@code online_state}。
     */
    private long sensorOfflineAfterMs = 180_000L;

    /** 传感器子设备离线扫描间隔（毫秒）。 */
    private long sensorScanIntervalMs = 60_000L;

    public String getDefaultProductKey() {
        return defaultProductKey;
    }

    public void setDefaultProductKey(String defaultProductKey) {
        this.defaultProductKey = defaultProductKey;
    }

    public String getDefaultTimezone() {
        return defaultTimezone;
    }

    public void setDefaultTimezone(String defaultTimezone) {
        this.defaultTimezone = defaultTimezone;
    }

    public int getEventPageSize() {
        return eventPageSize;
    }

    public void setEventPageSize(int eventPageSize) {
        this.eventPageSize = eventPageSize;
    }

    public int getEventMaxPageSize() {
        return eventMaxPageSize;
    }

    public void setEventMaxPageSize(int eventMaxPageSize) {
        this.eventMaxPageSize = eventMaxPageSize;
    }

    public long getSensorOfflineAfterMs() {
        return sensorOfflineAfterMs;
    }

    public void setSensorOfflineAfterMs(long sensorOfflineAfterMs) {
        this.sensorOfflineAfterMs = sensorOfflineAfterMs;
    }

    public long getSensorScanIntervalMs() {
        return sensorScanIntervalMs;
    }

    public void setSensorScanIntervalMs(long sensorScanIntervalMs) {
        this.sensorScanIntervalMs = sensorScanIntervalMs;
    }

    /**
     * 主控设备离线判定阈值（毫秒）：连续这么久没刷新心跳（{@code last_heartbeat}）即判离线，
     * 触发 DEVICE_OFFLINE 告警（文档 03 第 1 章 KeepAlive 60s，取 3 倍 = 180s 留余量）。
     * 仅作用于 MAIN；传感器子设备离线在 {@code SensorStatusService} 处理且不告警。
     */
    private long mainOfflineAfterMs = 180_000L;

    /** 主控离线扫描间隔（毫秒）。 */
    private long mainScanIntervalMs = 60_000L;

    public long getMainOfflineAfterMs() {
        return mainOfflineAfterMs;
    }

    public void setMainOfflineAfterMs(long mainOfflineAfterMs) {
        this.mainOfflineAfterMs = mainOfflineAfterMs;
    }

    public long getMainScanIntervalMs() {
        return mainScanIntervalMs;
    }

    public void setMainScanIntervalMs(long mainScanIntervalMs) {
        this.mainScanIntervalMs = mainScanIntervalMs;
    }
}

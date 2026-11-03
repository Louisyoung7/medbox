package com.medbox.server.dto.admin;

/**
 * 管理后台总览（见文档 02 §10.4.1）。
 *
 * <p>{@code mqttConnected} 取 {@code online_state='ONLINE'} 计数（device 表无独立 mqtt_connected 列，
 * 连接态由 online_state 表示，与 online 同源）。
 */
public record AdminDashboardOverview(
        DeviceStats devices,
        TodayDose todayDose,
        long elderCount,
        long planCount) {

    /** 设备网络状态统计。 */
    public record DeviceStats(long total, long online, long offline, long mqttConnected) {}

    /** 今日服药（按各老人设备本地时区）：应服 / 已服 / 漏服 / 待服。 */
    public record TodayDose(long totalSlots, long taken, long missed, long pending) {}
}

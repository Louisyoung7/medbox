package com.medbox.server.domain.enums;

/**
 * 设备事件类型（{@code device_event.event_type}，见文档 06 的 2.16）。
 *
 * <p>本枚举比 {@code feat/device-core} 实际写入的多两个值 —— 那是留给后续分支的：
 * 它们沿用同一张表、只需在本枚举里已有值上记录，**不改表结构**。
 */
public enum DeviceEventType {

    /** 设备登记（主控与每个子设备各记一条）。 */
    REGISTERED,

    /** 主控上线（MQTT status / 任意上行消息触发，仅状态跃变时记录）。 */
    ONLINE,

    /** 主控离线（MQTT 遗嘱，仅状态跃变时记录）。 */
    OFFLINE,

    /** 设备侧异常：门超时 / 识别失败 / 硬件故障 —— 由 {@code feat/device-fault} 写入。 */
    FAULT,

    /** 控制命令下发与回执 —— 由 {@code feat/device-command} 写入。 */
    COMMAND
}

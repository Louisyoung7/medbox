package com.medbox.server.dto.admin;

import java.time.OffsetDateTime;

/**
 * 设备网络状态行（见文档 02 §10.4.2）。
 *
 * <p>{@code status} 透出 {@code online_state} 字符串（ONLINE / OFFLINE），避免前端依赖枚举；
 * {@code elderName} 由 device.elder_id 左联 user.name 取得，无归属老人时为 null。
 */
public record AdminDeviceRow(
        String deviceId,
        String elderId,
        String elderName,
        String status,
        OffsetDateTime lastHeartbeat) {
}

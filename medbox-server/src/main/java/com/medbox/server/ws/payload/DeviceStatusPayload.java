package com.medbox.server.ws.payload;

/**
 * {@code DEVICE_STATUS} 帧载荷（见文档 04 第 3 章）。
 *
 * <p>只覆盖药箱主控：不再有传感器子设备状态帧，亦不再带 {@code deviceType} /
 * {@code parentDeviceId}。
 *
 * @param deviceId 药箱主控业务号
 * @param online   是否在线
 */
public record DeviceStatusPayload(String deviceId, boolean online) {
}

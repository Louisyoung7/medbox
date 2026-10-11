package com.medbox.server.ws.payload;

/**
 * {@code DEVICE_STATUS} 帧载荷（见文档 04 第 3 章）。
 *
 * <p>传感器子设备没有自己的 MQTT 连接，其在线状态由主控经 {@code up/sensor/heartbeat} 汇总上报，
 * 后端据此推送子设备的 {@code DEVICE_STATUS} —— 因此 {@code deviceType} 与 {@code parentDeviceId}
 * 是必填语义：子设备带 {@code parentDeviceId}，主控为 {@code null}
 * （受全局 {@code non_null} 影响，主控帧里该字段不出现）。
 *
 * @param deviceId        设备业务号
 * @param deviceType      MAIN / TEMP_HUMI / LIGHT / CAMERA
 * @param online          是否在线
 * @param parentDeviceId 子设备所属主控；主控帧为 {@code null}
 */
public record DeviceStatusPayload(String deviceId,
                                  String deviceType,
                                  boolean online,
                                  String parentDeviceId) {
}

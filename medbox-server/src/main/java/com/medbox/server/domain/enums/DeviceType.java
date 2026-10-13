package com.medbox.server.domain.enums;

import java.util.Locale;

/**
 * 设备类型（{@code device.device_type}，见文档 06 的 2.3）。
 *
 * <p><b>一个药箱 = 一个主控 + 若干传感器子设备</b>，全部登记在 {@code device} 表里，
 * 用本枚举区分、用 {@code parent_device_id} 指向所属药箱。药箱**不划分仓位 / 格子**，
 * 药品按"药箱 + 药品"维度管理（见文档 06 的 2.5）。
 *
 * <p><b>只有 {@link #MAIN} 有 MQTT 连接与遗嘱</b>：子设备没有自己的连接，
 * 由主控通过 {@code up/sensor/heartbeat} 每 60s 汇总上报其在线状态
 * （180s 未上报判离线，见 {@code feat/device-sensor}）。
 */
public enum DeviceType {

    /** 药箱主控：唯一的 MQTT 连接方，Topic 中的 {@code deviceId} 就是它。 */
    MAIN,

    /** 温湿度传感器子设备。 */
    TEMP_HUMI,

    /** 光照传感器子设备。 */
    LIGHT,

    /** 摄像头子设备：负责服药抓拍与药品识别。 */
    CAMERA;

    /** 宽松解析（忽略大小写与首尾空格）；不是合法类型返回 {@code null}，由调用方决定报错码。 */
    public static DeviceType parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DeviceType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 是否是药箱主控。 */
    public boolean isMain() {
        return this == MAIN;
    }

    /** 是否是传感器子设备（主控之外的全部类型）。 */
    public boolean isSensor() {
        return this != MAIN;
    }
}

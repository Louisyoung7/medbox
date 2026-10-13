package com.medbox.server.domain.enums;

import java.util.Locale;

/**
 * 设备在线状态（{@code device.online_state}，见文档 06 的 2.3）。
 *
 * <p><b>两条来源，互为补充</b>：
 * <ul>
 *   <li><b>主控</b>：MQTT 遗嘱 + retained status —— 设备连上发 {@code online:true}，
 *       异常掉线时 broker 自动代发 {@code online:false}，离线感知最及时；</li>
 *   <li><b>传感器子设备</b>：无 MQTT 连接与遗嘱，靠主控周期汇总上报，180s 未上报判离线。</li>
 * </ul>
 */
public enum OnlineState {

    ONLINE,

    OFFLINE;

    /** 宽松解析（忽略大小写与首尾空格）；不是合法状态返回 {@code null}。 */
    public static OnlineState parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OnlineState.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

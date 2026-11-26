package com.medbox.server.domain.enums;

import java.util.Locale;

/**
 * 设备在线状态（{@code device.online_state}，见文档 06 的 2.3）。
 *
 * <p><b>来源</b>：MQTT 遗嘱 + retained status —— 设备连上发 {@code online:true}，
 * 异常掉线时 broker 自动代发 {@code online:false}；任意上行消息也会顺带刷新在线态。
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

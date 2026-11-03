package com.medbox.server.dto.admin;

/**
 * MQTT 消息流（见文档 02 §10.4.4）。
 *
 * <p>上行来自 mqtt_message（仅上行去重）；下行来自 device_command（下行命令事实来源）。
 */
public record AdminMessageFlow(
        long upTotal,
        long upLastMinute,
        long downTotal,
        long downLastMinute) {
}

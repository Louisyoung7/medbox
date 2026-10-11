package com.medbox.server.mqtt.payload;

import lombok.Data;

/**
 * 设备在线状态 {@code .../status}（见文档 03 第 1 章）。
 *
 * <p>这是<b>唯一不带 {@code msgId}</b> 的上行消息：它是 retained 的状态消息
 * （遗嘱 topic 同样是它，payload 固定 {@code {"online":false}}），
 * 语义是"最新态"而非"一次事件"，去重没有意义 —— 只要最后一次值。
 *
 * <p>retained 使得后端（乃至任何订阅者）一订阅就能拿到设备的当前在线态。
 */
@Data
public class StatusPayload {

    private Boolean online;
}

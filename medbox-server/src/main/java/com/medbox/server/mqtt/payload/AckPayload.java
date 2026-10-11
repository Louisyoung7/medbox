package com.medbox.server.mqtt.payload;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 上行·下行回执 {@code up/ack}（见文档 03 第 3 章）。
 *
 * <p>设备处理完下行消息（计划 / 命令）后回执，用 {@code ackFor} 指回下行消息的 {@code msgId}；
 * 服务端 5s 未收到则重发，最多 3 次（{@code feat/backend-mqtt} 的 {@code PendingAckRegistry}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AckPayload extends BaseUpPayload {

    /** 对应的<b>下行</b>消息 msgId。 */
    private String ackFor;

    /** {@code OK} / {@code FAILED}。 */
    private String result;

    /** 失败时的简要原因，如 {@code medicine_not_found}；成功为 null。 */
    private String reason;

    /** result 是否为成功（大小写不敏感，缺字段视为非成功）。 */
    public boolean isOk() {
        return "OK".equalsIgnoreCase(result);
    }
}

package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 去重记录，对应 {@code mqtt_message} 表（见文档 06 的 2.15，由 V5__mqtt.sql 建）。
 *
 * <p>键是上行消息的 {@code msgId}（{@code {deviceId}-{10位序号}}，协议保证全局唯一）：
 * 同一条消息被重复投递（断线重连重发）时，抢不到唯一键，直接丢弃。
 *
 * <p>注意：这是<b>设备上行</b>的幂等，与 REST 侧 {@code X-Request-Id} 的 {@link IdempotencyRecord}
 * 是两回事（作用域不同、保留时长不同），不要合并成一张表。
 */
@Data
@TableName("mqtt_message")
public class MqttMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上行消息 msgId，如 {@code BOXA1001-0000000124}（唯一键，去重的依据）。 */
    private String msgId;

    /** 归属设备（解析自 topic 第 3 层）。 */
    private String deviceId;

    /** 完整上行 topic，便于排障。 */
    private String topic;

    /** 首次处理时间。 */
    private OffsetDateTime receivedAt;

    /** 过期时间；去重只需覆盖设备重发窗口（默认 7 天）。 */
    private OffsetDateTime expiresAt;
}

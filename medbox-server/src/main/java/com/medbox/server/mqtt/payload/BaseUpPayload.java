package com.medbox.server.mqtt.payload;

import lombok.Data;

/**
 * 上行消息公共字段（见文档 03 第 3 章）：<b>所有上行消息必带 {@code msgId}</b>。
 *
 * <p>{@code msgId} 格式固定 {@code {deviceId}-{10位递增序号}}（如 {@code BOXA1001-0000000124}），
 * 服务端按它去重；设备重启不能让序号归零。
 *
 * <p>子类不写 {@code @JsonIgnoreProperties}：Boot 默认已关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 * 设备多带字段不会让反序列化失败（后续演进允许固件加字段）。
 */
@Data
public abstract class BaseUpPayload {

    /** 消息幂等 ID，如 {@code BOXA1001-0000000124}。 */
    private String msgId;

    /** 设备侧时间戳（epoch millis）；仅作参考，服务端判定一律以入库时间 / 服务端时钟为准。 */
    private Long ts;
}

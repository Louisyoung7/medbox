package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.medbox.server.domain.enums.OnlineState;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 设备，对应 {@code device} 表（见文档 06 的 2.3，由 V1 建、V28 收敛）。
 *
 * <p><b>系统只建模药箱主控一种设备</b>：不再有传感器 / 摄像头子设备记录，
 * 也没有 {@code device_type} / {@code parent_device_id}。环境遥测直接挂在本实体的
 * {@code deviceId} 下，摄像头抓拍也用同一个 {@code deviceId} 经 HTTP 上报。
 *
 * <p><b>主键必须 {@code IdType.AUTO}</b>：表主键是 BIGSERIAL，MyBatis-Plus 默认雪花 ID
 * 会往自增列里塞一个巨大的值（见 README 第 6 节）。
 */
@Data
@TableName("device")
public class Device {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID，由设备固件生成并固化；同时也是 MQTT 的 clientId（文档 03 第 1 章）。 */
    private String deviceId;

    /** 产品 key，Topic 第 2 层（{@code medbox/{productKey}/{deviceId}/...}）。 */
    private String productKey;

    /** 设备凭据：**预留字段，本期 EMQX 匿名连接不使用**。 */
    private String secret;

    /** 归属老人（{@code user.user_id}）。 */
    private String elderId;

    /** 设备本地时区（服药时间按此换算），默认 {@code Asia/Shanghai}。 */
    private String timezone;

    /** 在线状态，见 {@link OnlineState}；建表默认 {@code OFFLINE}。 */
    private OnlineState onlineState;

    /** 最近一次心跳 / 连接建立时间。 */
    private OffsetDateTime lastHeartbeat;

    private OffsetDateTime createdAt;
}

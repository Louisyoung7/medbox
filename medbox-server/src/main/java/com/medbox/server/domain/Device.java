package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 设备，对应 {@code device} 表（见文档 06 的 2.3，由 V1 建）。
 *
 * <p><b>为什么在 {@code feat/backend-mqtt} 里提前引入</b>：本分支要落实文档 03 第 1 章的
 * "遗嘱与在线状态维护" —— 主控上线 / LWT 离线都要写 {@code online_state} 与 {@code last_heartbeat}，
 * 而 {@code device} 表 V1 已建好，这里不过界建表，只补实体与 Mapper；
 * {@code feat/device-core} 后续直接复用扩展（设备注册 / 详情等），不必重建。
 *
 * <p><b>本分支刻意不做的事</b>：不实现 {@code DeviceOwnerResolver} 扩展点
 * （设备归属老人的解析归 {@code feat/device-core}），本分支只碰状态字段。
 *
 * <p><b>{@code feat/device-core} 的改动</b>：{@code deviceType} 与 {@code onlineState}
 * 由 {@code String} 收紧为枚举（与 {@code RelationRole} / {@code RelationStatus} 同一实践，
 * MyBatis-Plus 默认按枚举名映射 VARCHAR 列），登记与查询时不再有手写字符串常量的余地。
 *
 * <p><b>主键必须 {@code IdType.AUTO}</b>：表主键是 BIGSERIAL，MyBatis-Plus 默认雪花 ID（ASSIGN_ID）
 * 会往自增列里塞一个巨大的值（见 README 第 6 节）。
 */
@Data
@TableName("device")
public class Device {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID，如 {@code BOXA1001}；同时也是 MQTT 的 clientId（文档 03 第 1 章）。 */
    private String deviceId;

    /** 药箱主控 / 传感器子设备，见 {@link DeviceType}。 */
    private DeviceType deviceType;

    /** 子设备指向所属药箱的 {@code device_id}；主控为空。 */
    private String parentDeviceId;

    /** 产品 key，Topic 第 2 层（{@code medbox/{productKey}/{deviceId}/...}）。 */
    private String productKey;

    /** 设备凭据：**预留字段，本期 EMQX 匿名连接不使用**。 */
    private String secret;

    /** 归属老人（{@code user.user_id}）；子设备也填，与父设备一致，便于行级权限与按老人查询。 */
    private String elderId;

    /** 设备本地时区（服药时间按此换算），默认 {@code Asia/Shanghai}。 */
    private String timezone;

    /** 在线状态，见 {@link OnlineState}；建表默认 {@code OFFLINE}。 */
    private OnlineState onlineState;

    /** 最近一次心跳 / 连接建立时间。 */
    private OffsetDateTime lastHeartbeat;

    private OffsetDateTime createdAt;
}

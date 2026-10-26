package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 环境遥测样本，对应 {@code env_sample} 表（见文档 06 的 2.8）。
 *
 * <p><b>按传感器分别入库</b>：温湿度传感器上报的 {@code temperature} / {@code humidity} 与
 * 光照传感器上报的 {@code lux} 各成一行（其余数值列为 {@code NULL}），便于按传感器查曲线、
 * 也便于排障（看单个传感器是否掉线）。{@code gateway_device_id} 记上报它的主控，
 * {@code elder_id} 冗余归属，便于行级权限与按老人查询。
 *
 * <p><b>数值列用 {@code BigDecimal}</b>：表列是 {@code NUMERIC}，JDBC 映射到 {@code BigDecimal}，
 * 实体若用 {@code Double} 会触发 {@code NUMERIC} → {@code Double} 的精度/类型映射异常。
 *
 * <p><b>主键 {@code IdType.AUTO}</b>：表主键是 BIGSERIAL，与 {@link Device} 同口径。
 */
@Data
@TableName("env_sample")
public class EnvSample {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 传感器自身 device_id（子设备编号）。 */
    private String deviceId;

    /** 上报它的药箱主控 device_id。 */
    private String gatewayDeviceId;

    /** 冗余归属老人 user_id（与传感器子设备的 elder_id 一致）。 */
    private String elderId;

    /** 温度（℃），仅温湿度传感器上报时非空。 */
    private BigDecimal temperature;

    /** 湿度（%），仅温湿度传感器上报时非空。 */
    private BigDecimal humidity;

    /** 光照强度（lux），仅光照传感器上报时非空。 */
    private BigDecimal lux;

    /** 采样时间（设备侧 ts 转 UTC，缺省服务端当前时刻）。 */
    private OffsetDateTime sampleTime;
}

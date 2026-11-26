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
 * <p><b>直接挂主控</b>：温湿度与光照由药箱主控经 MQTT 上报，{@code deviceId} 即主控
 * {@code device.device_id}；不再有传感器子设备 ID 与 {@code gateway_device_id}。
 * 温湿度与光照可在不同时刻上报，各成一行（其余数值列为 {@code NULL}），
 * {@code elder_id} 冗余归属便于行级权限与按老人查询。
 *
 * <p><b>数值列用 {@code BigDecimal}</b>：表列是 {@code NUMERIC}，JDBC 映射到 {@code BigDecimal}，
 * 实体若用 {@code Double} 会触发精度/类型映射异常。
 *
 * <p><b>主键 {@code IdType.AUTO}</b>：表主键是 BIGSERIAL，与 {@link Device} 同口径。
 */
@Data
@TableName("env_sample")
public class EnvSample {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 药箱主控 device_id（V28 后与上报主控合一）。 */
    private String deviceId;

    /** 冗余归属老人 user_id。 */
    private String elderId;

    /** 温度（℃），仅温湿度上报时非空。 */
    private BigDecimal temperature;

    /** 湿度（%），仅温湿度上报时非空。 */
    private BigDecimal humidity;

    /** 光照强度（lux），仅光照上报时非空。 */
    private BigDecimal lux;

    /** 采样时间（设备侧 ts 转 UTC，缺省服务端当前时刻）。 */
    private OffsetDateTime sampleTime;
}

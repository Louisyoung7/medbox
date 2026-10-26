package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

/**
 * 老人级告警阈值，对应 {@code alarm_setting} 表（见文档 06 的 2.10，由 V1 建）。
 *
 * <p><b>本类由 {@code feat/dispense-consume} 引入</b>，彼时只映射 {@code missTolerateMin}
 * （漏服容忍时长，老人级，是"计划级 > 老人级 > 15 分钟"三级优先级的中间一级，06 §2.6.1）。
 * {@code expiring_ahead_days} / {@code env_threshold} JSONB 由 {@code feat/alarm-core} 补齐。
 *
 * <p><b>{@code envThreshold}</b> 为 JSONB（结构见 {@link EnvThreshold}），用
 * {@link com.medbox.server.config.EnvThresholdJsonbTypeHandler} 读写；类级 {@code autoResultMap = true}
 * 是必须的（与 {@code MedPlan} 一致），否则结果映射不会带上该处理器、读出来仍是 {@code PGobject}。
 *
 * <p>表 {@code elder_id} 为 UNIQUE，每老人至多一行；查不到行即视为"未配置老人级阈值"，落回系统默认。
 */
@Data
@TableName(value = "alarm_setting", autoResultMap = true)
public class AlarmSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属老人（{@code user.user_id}，ELDER），UNIQUE。 */
    private String elderId;

    /** 临期提前预警天数（feat/alarm-core 用）。 */
    private Integer expiringAheadDays;

    /** 漏服容忍时长（分钟），老人级；为空表示沿用系统默认 15 分钟。 */
    private Integer missTolerateMin;

    /** 环境上下限（JSONB，见 {@link EnvThreshold}）；为空表示不启用环境阈值判定。 */
    @TableField(typeHandler = com.medbox.server.config.EnvThresholdJsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private EnvThreshold envThreshold;

    private OffsetDateTime updatedAt;
}

package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 老人级告警阈值，对应 {@code alarm_setting} 表（见文档 06 的 2.10，由 V1 建）。
 *
 * <p><b>本期（feat/dispense-consume）只映射 {@code missTolerateMin}</b>：漏服容忍时长（老人级），
 * 是"计划级 > 老人级 > 15 分钟"三级优先级的中间一级（06 §2.6.1）。其余列
 * （{@code expiring_ahead_days} / {@code env_threshold} JSONB）留待 {@code feat/alarm-core} 扩展，
 * 本期不引 JSONB TypeHandler。
 *
 * <p>表 {@code elder_id} 为 UNIQUE，每老人至多一行；查不到行即视为"未配置老人级阈值"，落回系统默认。
 */
@Data
@TableName("alarm_setting")
public class AlarmSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属老人（{@code user.user_id}，ELDER），UNIQUE。 */
    private String elderId;

    /** 临期提前预警天数（feat/alarm-core 用，本期不读）。 */
    private Integer expiringAheadDays;

    /** 漏服容忍时长（分钟），老人级；为空表示沿用系统默认 15 分钟。 */
    private Integer missTolerateMin;

    private OffsetDateTime updatedAt;
}

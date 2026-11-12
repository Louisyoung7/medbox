package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.medbox.server.config.AlarmRuleJsonbTypeHandler;
import com.medbox.server.config.SlotsJsonbTypeHandler;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

/**
 * 服药计划，对应 {@code med_plan} 表（见文档 06 的 2.6）。
 *
 * <p><b>一老人一计划</b>（{@code elder_id} 唯一）：{@code slots} 只描述「几点开几号格」，
 * 格号 1 / 2 / 3 固定对应早 / 中 / 晚，不描述、不关联任何药品。缺失的格号 = 该格不排程。
 *
 * <p><b>{@code slots} / {@code alarm_rule} 是 JSONB</b>：用 {@link SlotsJsonbTypeHandler} /
 * {@link AlarmRuleJsonbTypeHandler} 读写，类级 {@code autoResultMap = true} 是必须的。
 *
 * <p><b>{@code slots[].time} 原样存设备本地墙钟</b>（如 {@code "08:00"}），不转 UTC；
 * UTC 换算在 {@code PlanSlotResolver} 里按设备时区现场算。
 */
@Data
@TableName(value = "med_plan", autoResultMap = true)
public class MedPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID，如 {@code p-1001}（V9 的 med_plan_biz_id_seq）。 */
    private String planId;

    /** 归属老人（{@code user.user_id}，ELDER），唯一。 */
    private String elderId;

    /** 方案名（选填）。 */
    private String name;

    /** 「格号 → 开格时间」，如 [{"cellNo":1,"time":"08:00"}]；1~3 项、格号不重复。 */
    @TableField(typeHandler = SlotsJsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private List<PlanSlot> slots;

    /** 重复规则，本期仅 DAILY。 */
    private String repeatRule;

    /** 漏服容忍（分钟），可空=沿用上一级。 */
    private Integer missAlarmAfterMin;

    /** 告警规则，如 {"miss":true,"env":true}。 */
    @TableField(typeHandler = AlarmRuleJsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Boolean> alarmRule;

    /** 计划状态，ENABLED / DISABLED。 */
    private String status;

    /** 下发状态：PENDING / SYNCED_TO_DEVICE / SYNC_FAILED。 */
    private String syncState;

    private OffsetDateTime createdAt;
}

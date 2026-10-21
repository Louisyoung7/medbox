package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.medbox.server.config.AlarmRuleJsonbTypeHandler;
import com.medbox.server.config.TimesJsonbTypeHandler;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

/**
 * 服药计划头，对应 {@code med_plan} 表（见文档 06 的 2.6，由 V1 建；V9 补 {@code sync_state} 列）。
 *
 * <p><b>{@code times} / {@code alarm_rule} 是 JSONB</b>：用 {@link TimesJsonbTypeHandler} /
 * {@link AlarmRuleJsonbTypeHandler} 读写，类级 {@code autoResultMap = true} 是必须的
 * （否则 MyBatis-Plus 的结果映射不带处理器，读出来是 {@code PGobject}）。
 *
 * <p><b>{@code times} 原样存本地墙钟</b>（如 {@code "08:00"}），不转 UTC；UTC 换算在
 * {@code PlanService.nextDoses} 里按设备时区现场算。
 *
 * <b>{@code status} / {@code repeatRule} / {@code syncState}</b> 都是 VARCHAR：前者
 * 取值 ENABLED/DISABLED，后者本期只有 DAILY，{@code syncState} 初始 PENDING（真实下发状态机归
 * feat/plan-sync）。允许值由 Service 层用 {@code BizAssert} 校验，不引枚举以免超 06 schema。
 */
@Data
@TableName(value = "med_plan", autoResultMap = true)
public class MedPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID，如 {@code p-1001}（V9 的 med_plan_biz_id_seq）。 */
    private String planId;

    /** 归属老人（{@code user.user_id}，ELDER）。 */
    private String elderId;

    /** 方案名，如"早餐后"。 */
    private String name;

    /** 服药时间（设备本地墙钟），如 ["08:00","20:00"]。 */
    @TableField(typeHandler = TimesJsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private List<String> times;

    /** 重复规则，本期仅 DAILY。 */
    private String repeatRule;

    /** 漏服容忍（分钟），可空=沿用上一级。 */
    private Integer missAlarmAfterMin;

    /** 告警规则，如 {"miss":true,"wrongDrug":false}。 */
    @TableField(typeHandler = AlarmRuleJsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Boolean> alarmRule;

    /** 计划状态，ENABLED / DISABLED。 */
    private String status;

    /** 下发状态，本期恒为 PENDING（feat/plan-sync 接管）。 */
    private String syncState;

    private OffsetDateTime createdAt;
}

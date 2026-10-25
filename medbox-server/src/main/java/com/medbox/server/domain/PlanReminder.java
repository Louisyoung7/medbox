package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 服药提醒去重记录（feat/plan-reminder，见文档 06 的 2.6.x）。
 *
 * <p><b>用途</b>：云端定时扫描每个服药槽只推一次 {@code REMINDER} 帧；{@code dedup_key}
 * 唯一索引保证重启 / 重扫同一窗口不重复推送。REMINDER 不是告警，故隔离表、不进 {@code alarm} 列表。
 *
 * <p><b>主键</b>：仅需自增主键与 {@code dedup_key} 唯一约束，无需业务号（提醒记录不对外引用）。
 */
@Data
@TableName("plan_reminder")
public class PlanReminder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 去重键，形如 {@code REMINDER:{planId}:{slotUTC}}；唯一索引冲突即跳过。 */
    private String dedupKey;

    /** 关联计划（{@code med_plan.plan_id}）。 */
    private String planId;

    /** 归属老人（{@code user.user_id}，ELDER）。 */
    private String elderId;

    /** 计划服药槽（UTC 瞬时）。 */
    private OffsetDateTime slotUtc;

    private OffsetDateTime createdAt;
}

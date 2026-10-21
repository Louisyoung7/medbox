package com.medbox.server.dto.plan;

import com.medbox.server.domain.MedPlan;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 计划详情回显（含明细），也是创建 / 修改 / 启停后的回显。
 *
 * <p><b>{@code syncState} 本期恒为 PENDING</b>：真实的下发状态机（SYNCED_TO_DEVICE /
 * SYNC_FAILED）由后续 {@code feat/plan-sync} 接管，见 06 §2.6 与 V9 的说明。
 *
 * @param planId            计划业务 ID
 * @param elderId           归属老人业务 ID
 * @param name              方案名
 * @param times             服药时间（本地墙钟）
 * @param repeatRule        重复规则
 * @param missAlarmAfterMin 漏服容忍分钟
 * @param alarmRule         告警规则
 * @param status            计划状态 ENABLED / DISABLED
 * @param syncState         下发状态（本期恒 PENDING）
 * @param createdAt         创建时间（UTC）
 * @param items             药品明细
 */
public record PlanResponse(
    String planId,
    String elderId,
    String name,
    List<String> times,
    String repeatRule,
    Integer missAlarmAfterMin,
    Map<String, Boolean> alarmRule,
    String status,
    String syncState,
    OffsetDateTime createdAt,
    List<PlanItemResponse> items
) {
    public static PlanResponse from(MedPlan plan, List<PlanItemResponse> items) {
        return new PlanResponse(
            plan.getPlanId(),
            plan.getElderId(),
            plan.getName(),
            plan.getTimes(),
            plan.getRepeatRule(),
            plan.getMissAlarmAfterMin(),
            plan.getAlarmRule(),
            plan.getStatus(),
            plan.getSyncState(),
            plan.getCreatedAt(),
            items);
    }
}

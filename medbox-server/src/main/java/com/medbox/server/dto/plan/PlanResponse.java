package com.medbox.server.dto.plan;

import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.PlanSlot;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 计划详情回显，也是创建 / 修改 / 启停后的回显（只含格号排程，不含药品信息）。
 *
 * @param planId            计划业务 ID
 * @param elderId           归属老人业务 ID
 * @param name              方案名
 * @param slots             格号 → 开格时间（设备本地墙钟）
 * @param repeatRule        重复规则
 * @param missAlarmAfterMin 漏服容忍分钟
 * @param alarmRule         告警规则
 * @param status            计划状态 ENABLED / DISABLED
 * @param syncState         下发状态 PENDING / SYNCED_TO_DEVICE / SYNC_FAILED
 * @param createdAt         创建时间（UTC）
 */
public record PlanResponse(
    String planId,
    String elderId,
    String name,
    List<PlanSlot> slots,
    String repeatRule,
    Integer missAlarmAfterMin,
    Map<String, Boolean> alarmRule,
    String status,
    String syncState,
    OffsetDateTime createdAt
) {
    public static PlanResponse from(MedPlan plan) {
        return new PlanResponse(
            plan.getPlanId(),
            plan.getElderId(),
            plan.getName(),
            plan.getSlots(),
            plan.getRepeatRule(),
            plan.getMissAlarmAfterMin(),
            plan.getAlarmRule(),
            plan.getStatus(),
            plan.getSyncState(),
            plan.getCreatedAt());
    }
}

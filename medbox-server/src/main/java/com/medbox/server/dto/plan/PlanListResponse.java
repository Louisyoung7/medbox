package com.medbox.server.dto.plan;

import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.PlanSlot;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 计划列表项（{@code GET /plans?elderId=}）；一老人一计划，故返回 0 或 1 条。
 *
 * @param planId    计划业务 ID
 * @param elderId   归属老人业务 ID
 * @param name      方案名
 * @param slots     格号 → 开格时间（设备本地墙钟）
 * @param repeatRule 重复规则
 * @param status    计划状态
 * @param syncState 下发状态
 * @param createdAt 创建时间（UTC）
 */
public record PlanListResponse(
    String planId,
    String elderId,
    String name,
    List<PlanSlot> slots,
    String repeatRule,
    String status,
    String syncState,
    OffsetDateTime createdAt
) {
    public static PlanListResponse from(MedPlan plan) {
        return new PlanListResponse(
            plan.getPlanId(),
            plan.getElderId(),
            plan.getName(),
            plan.getSlots(),
            plan.getRepeatRule(),
            plan.getStatus(),
            plan.getSyncState(),
            plan.getCreatedAt());
    }
}

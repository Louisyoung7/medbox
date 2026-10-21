package com.medbox.server.dto.plan;

import com.medbox.server.domain.MedPlan;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 计划列表项（{@code GET /plans?elderId=}），含药品概览，不放长文本明细。
 *
 * @param planId       计划业务 ID
 * @param elderId      归属老人业务 ID
 * @param name         方案名
 * @param times        服药时间（本地墙钟）
 * @param repeatRule   重复规则
 * @param status       计划状态
 * @param syncState    下发状态
 * @param itemCount    明细条数
 * @param medicineNames 本计划涉及的药品名称概览（去重、去 null）
 * @param createdAt    创建时间（UTC）
 */
public record PlanListResponse(
    String planId,
    String elderId,
    String name,
    List<String> times,
    String repeatRule,
    String status,
    String syncState,
    int itemCount,
    List<String> medicineNames,
    OffsetDateTime createdAt
) {
    public static PlanListResponse from(MedPlan plan, int itemCount, List<String> medicineNames) {
        return new PlanListResponse(
            plan.getPlanId(),
            plan.getElderId(),
            plan.getName(),
            plan.getTimes(),
            plan.getRepeatRule(),
            plan.getStatus(),
            plan.getSyncState(),
            itemCount,
            medicineNames,
            plan.getCreatedAt());
    }
}

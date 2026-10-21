package com.medbox.server.dto.plan;

import com.medbox.server.domain.MedPlanItem;

/**
 * 计划明细回显。
 *
 * @param itemId    明细业务 ID
 * @param medicineId 药品业务 ID
 * @param dose      单次剂量
 * @param unit      单位
 * @param note      服用说明
 * @param sortOrder 展示排序
 */
public record PlanItemResponse(
    String itemId,
    String medicineId,
    String dose,
    String unit,
    String note,
    Integer sortOrder
) {
    public static PlanItemResponse from(MedPlanItem item) {
        return new PlanItemResponse(
            item.getItemId(),
            item.getMedicineId(),
            item.getDose(),
            item.getUnit(),
            item.getNote(),
            item.getSortOrder());
    }
}

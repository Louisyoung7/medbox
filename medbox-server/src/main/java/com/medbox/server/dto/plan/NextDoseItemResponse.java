package com.medbox.server.dto.plan;

import com.medbox.server.domain.MedPlanItem;

/**
 * 未来待服时间线中，某次服药所需的药品。
 *
 * <p><b>{@code medicineName} 是关联查出来的</b>：时间线复用计划明细，由 Service 批量按
 * {@code medicineId} 查药名后填进来，避免逐条查库的 N+1。
 *
 * @param medicineId   药品业务 ID
 * @param medicineName 药品名称（关联查出，供展示）
 * @param dose         单次剂量
 * @param unit         单位
 * @param note         服用说明
 */
public record NextDoseItemResponse(
    String medicineId,
    String medicineName,
    String dose,
    String unit,
    String note
) {
    public static NextDoseItemResponse from(MedPlanItem item, String medicineName) {
        return new NextDoseItemResponse(
            item.getMedicineId(),
            medicineName,
            item.getDose(),
            item.getUnit(),
            item.getNote());
    }
}

package com.medbox.server.dto.plan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 计划明细入参（一次计划含的某一种药）。
 *
 * @param medicineId 药品业务 ID（必填）
 * @param dose      单次剂量，如 "1"
 * @param unit      单位，如 "片"
 * @param note      服用说明，如"餐后"
 */
public record PlanItemRequest(
    @NotBlank(message = "item.medicineId 不能为空")
    @Size(max = 64, message = "item.medicineId 超长")
    String medicineId,

    @Size(max = 32, message = "item.dose 超长")
    String dose,

    @Size(max = 16, message = "item.unit 超长")
    String unit,

    @Size(max = 64, message = "item.note 超长")
    String note
) {
}

package com.medbox.server.dto.plan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /plans/{planId}/status} 的入参：整体启停计划。
 *
 * @param status ENABLED / DISABLED
 */
public record PlanStatusRequest(
    @NotBlank(message = "status 不能为空")
    @Size(max = 16, message = "status 超长")
    String status
) {
}

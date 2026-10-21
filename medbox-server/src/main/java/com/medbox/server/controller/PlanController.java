package com.medbox.server.controller;

import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.R;
import com.medbox.server.dto.plan.NextDoseResponse;
import com.medbox.server.dto.plan.PlanListResponse;
import com.medbox.server.dto.plan.PlanResponse;
import com.medbox.server.dto.plan.PlanSaveRequest;
import com.medbox.server.dto.plan.PlanStatusRequest;
import com.medbox.server.service.PlanService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服药计划接口（见文档 02 第 6 章、06 的 2.6 / 2.6.1）。
 *
 * <p>只做参数绑定与 {@code R<T>} 包装，权限 / 校验 / 时区换算 / 库存校验全在
 * {@link PlanService}；未登录由 {@code AuthInterceptor} 统一兜 40101。
 */
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /** POST /plans：创建计划（elderId 必填）。 */
    @PostMapping
    public R<PlanResponse> create(@Valid @RequestBody PlanSaveRequest request) {
        return R.ok(planService.create(request));
    }

    /** PUT /plans/{planId}：整体覆盖修改（归属老人与状态不变）。 */
    @PutMapping("/{planId}")
    public R<PlanResponse> update(@PathVariable String planId,
                                  @Valid @RequestBody PlanSaveRequest request) {
        return R.ok(planService.update(planId, request));
    }

    /** PATCH /plans/{planId}/status：整体启停。 */
    @PatchMapping("/{planId}/status")
    public R<PlanResponse> updateStatus(@PathVariable String planId,
                                        @Valid @RequestBody PlanStatusRequest request) {
        return R.ok(planService.updateStatus(planId, request));
    }

    /** DELETE /plans/{planId}：删除并级联删明细。 */
    @DeleteMapping("/{planId}")
    public R<Void> delete(@PathVariable String planId) {
        planService.delete(planId);
        return R.ok();
    }

    /** GET /plans/{planId}：详情（含明细）。 */
    @GetMapping("/{planId}")
    public R<PlanResponse> detail(@PathVariable String planId) {
        return R.ok(planService.detail(planId));
    }

    /** GET /plans?elderId=：分页列表（含药品概览）。 */
    @GetMapping
    public R<PageResult<PlanListResponse>> list(
            @RequestParam("elderId") String elderId,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return R.ok(planService.list(elderId, page, size));
    }

    /** GET /plans/{planId}/next-doses?days=N：未来待服时间线（仅 DAILY）。 */
    @GetMapping("/{planId}/next-doses")
    public R<List<NextDoseResponse>> nextDoses(
            @PathVariable String planId,
            @RequestParam(value = "days", required = false) Integer days) {
        return R.ok(planService.nextDoses(planId, days));
    }
}

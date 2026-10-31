package com.medbox.server.controller;

import com.medbox.server.dto.R;
import com.medbox.server.dto.adherence.AdherenceResponse;
import com.medbox.server.service.AdherenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服药依从性统计接口（{@code feat/record-report}，见文档 02 第 7 章、07）。
 *
 * <p>仅做参数绑定与 {@link R} 包装；权限 / 统计口径 / 时区分桶全在 {@link AdherenceService}。
 * 未登录由 {@code AuthInterceptor} 统一兜 40101；越权 / PENDING 由 {@code AccessService} 转 40301 / 40302。
 *
 * <p>端点：{@code GET /api/v1/adherence?elderId=&period=day|week|month}（顶层，不在 /records 下）。
 */
@RestController
@RequestMapping("/api/v1/adherence")
public class AdherenceController {

    private final AdherenceService adherenceService;

    public AdherenceController(AdherenceService adherenceService) {
        this.adherenceService = adherenceService;
    }

    /** GET /api/v1/adherence：返回周期内按时率 / 漏服 / 本地日趋势。 */
    @GetMapping
    public R<AdherenceResponse> get(@RequestParam("elderId") String elderId,
                                    @RequestParam("period") String period) {
        return R.ok(adherenceService.compute(elderId, period));
    }
}

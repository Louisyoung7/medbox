package com.medbox.server.controller;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.dto.R;
import com.medbox.server.dto.admin.AdminAdherenceDashboard;
import com.medbox.server.dto.admin.AdminDashboardOverview;
import com.medbox.server.dto.admin.AdminDeviceRow;
import com.medbox.server.dto.admin.AdminMessageFlow;
import com.medbox.server.service.AdminDashboardService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台监控看板接口（只读聚合，见文档 02 §10.4）。
 *
 * <p>所有端点落在 {@code /api/v1/admin/**}，由 {@code AdminAuthInterceptor} 强制 ADMIN 角色（非 ADMIN → 40301），
 * 登录态由 {@code AuthInterceptor} 兜底（未登录 → 40101）。本类不判角色。
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    public AdminDashboardController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** 总览：设备网络状态 + 今日服药 + 老人/计划数（02 §10.4.1）。 */
    @GetMapping("/overview")
    public R<AdminDashboardOverview> overview() {
        return R.ok(dashboardService.overview());
    }

    /** 设备网络状态：最近设备列表（02 §10.4.2）。 */
    @GetMapping("/devices")
    public R<List<AdminDeviceRow>> devices(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(dashboardService.devices(limit));
    }

    /** 服药依从率：趋势 / 漏服时段 / 按老人排行（02 §10.4.3）。 */
    @GetMapping("/adherence")
    public R<AdminAdherenceDashboard> adherence(@RequestParam(defaultValue = "week") String granularity) {
        if (!"week".equals(granularity) && !"month".equals(granularity)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "granularity 仅支持 week | month");
        }
        return R.ok(dashboardService.adherence(granularity));
    }

    /** MQTT 消息流：上行/下行累计与近一分钟吞吐（02 §10.4.4）。 */
    @GetMapping("/messages")
    public R<AdminMessageFlow> messages() {
        return R.ok(dashboardService.messages());
    }
}

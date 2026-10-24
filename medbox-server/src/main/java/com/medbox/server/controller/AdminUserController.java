package com.medbox.server.controller;

import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.R;
import com.medbox.server.dto.admin.AdminUserRow;
import com.medbox.server.service.AdminUserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台接口（测试数据清理，dev/QA 工具，见文档 02 第 10 章）。
 *
 * <p>所有端点落在 {@code /api/v1/admin/**}，由 {@code AdminAuthInterceptor} 强制 ADMIN 角色（非 ADMIN → 40301），
 * 登录态由 {@code AuthInterceptor} 兜底（未登录 → 40101）。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /** 列出用户（供挑测试号）：status / role / keyword 筛选 + 分页（见 02 第 10.1）。 */
    @GetMapping
    public R<PageResult<AdminUserRow>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(adminUserService.list(status, role, keyword, page, size));
    }

    /** 硬删 + 级联清理（见 02 第 10.2）：ACTIVE 拒绝 40902、不存在 40401。 */
    @DeleteMapping("/{userId}")
    public R<Void> purge(@PathVariable String userId) {
        adminUserService.purge(userId);
        return R.ok();
    }
}

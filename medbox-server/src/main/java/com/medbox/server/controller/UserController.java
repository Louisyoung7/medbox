package com.medbox.server.controller;

import com.medbox.server.dto.R;
import com.medbox.server.dto.user.UserProfileResponse;
import com.medbox.server.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（见文档 02 第 3 章）。
 *
 * <p>这些接口都受 {@code AuthInterceptor} 保护（{@code /api/v1/**} 需带 access token），
 * 无需在本类里再判登录态。
 *
 * <p>监护关系的申请 / 确认 / 解除接口属 P1 {@code feat/guardian-relation}，本分支不实现。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 当前用户资料与角色。 */
    @GetMapping("/me")
    public R<UserProfileResponse> me() {
        return R.ok(userService.me());
    }
}

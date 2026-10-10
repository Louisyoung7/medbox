package com.medbox.server.controller;

import com.medbox.server.common.TraceContext;
import com.medbox.server.dto.R;
import com.medbox.server.dto.auth.LoginRequest;
import com.medbox.server.dto.auth.RefreshRequest;
import com.medbox.server.dto.auth.RegisterRequest;
import com.medbox.server.dto.auth.TokenResponse;
import com.medbox.server.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（见文档 02 第 3 章）：注册 / 登录 / 刷新。
 *
 * <p>这三个接口**不校验登录态**（{@code /api/v1/auth/**} 已在 {@code WebMvcConfig} 里放行），
 * 校验失败按 {@code 40001}（参数）/ {@code 40102}（账号或密码错误）由全局异常处理统一输出。
 *
 * <p>{@code GET /users/me} 不在本分支（属 {@code feat/backend-authz}）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册（注册成功即登录）。
     *
     * <p>{@code X-Request-Id} 幂等在这里生效：同一个号重复提交只创建 1 个账号，并回放首次响应。
     */
    @PostMapping("/register")
    public R<TokenResponse> register(@Valid @RequestBody RegisterRequest request,
                                     @RequestHeader(value = TraceContext.REQUEST_ID_HEADER, required = false)
                                     String requestId) {
        return R.ok(authService.register(request, TraceContext.sanitize(requestId)));
    }

    /** 登录：account 为手机号或用户名。 */
    @PostMapping("/login")
    public R<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    /** 刷新：用旧的 refresh token 换一对新的（轮换，旧的立即作废）。 */
    @PostMapping("/refresh")
    public R<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return R.ok(authService.refresh(request.refreshToken()));
    }
}

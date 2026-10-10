package com.medbox.server.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（{@code POST /auth/login}）：{@code account} 可以是手机号或用户名。
 */
public record LoginRequest(

        @NotBlank(message = "account 不能为空")
        String account,

        @NotBlank(message = "password 不能为空")
        String password) {
}

package com.medbox.server.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新请求（{@code POST /auth/refresh}）：用旧的 refresh token 换一对新的（轮换，旧的立即作废）。
 */
public record RefreshRequest(

        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken) {
}

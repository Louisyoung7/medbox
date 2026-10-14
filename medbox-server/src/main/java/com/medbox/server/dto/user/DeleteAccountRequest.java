package com.medbox.server.dto.user;

import jakarta.validation.constraints.NotBlank;

/**
 * 注销账号请求（{@code POST /users/me/delete}，见文档 02 的 3.1）。
 *
 * <p>注销**不可逆**，故后端要求出示当前账号的密码做二次确认 —— 前端弹窗只是交互，
 * 接口才是唯一入口（拿到 access token 就能注销的话，一次 XSS / 丢手机就足以毁号）。
 *
 * @param password 当前账号的明文密码；由 {@code PasswordHasher} 做 BCrypt 比对，不符返回 40102
 */
public record DeleteAccountRequest(

        @NotBlank(message = "password 不能为空")
        String password) {
}

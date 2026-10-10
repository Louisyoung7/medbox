package com.medbox.server.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求（{@code POST /auth/register}，见文档 02 第 3 章）。
 *
 * <p>注册成功即登录（响应与登录同构）。 {@code username} / {@code name} 选填；监护人可用本接口
 * **为老人代建账号**（{@code role=ELDER}）。护理 / 医生不在此注册（见 02 第 3 章注释）。
 */
public record RegisterRequest(

        /** 登录手机号，唯一。 */
        @NotBlank(message = "phone 不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "phone 格式不正确")
        String phone,

        /** 明文密码（服务端 BCrypt 落库），6~64 位。 */
        @NotBlank(message = "password 不能为空")
        @Size(min = 6, max = 64, message = "password 长度需为 6~64 位")
        String password,

        /** ELDER / GUARDIAN，其它值按 40001 处理。 */
        @NotBlank(message = "role 不能为空")
        String role,

        /** 登录用户名（可空），与 phone 二选一登录。 */
        @Size(max = 64, message = "username 超长")
        String username,

        /** 展示名（可空）。 */
        @Size(max = 64, message = "name 超长")
        String name) {
}

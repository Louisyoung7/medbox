package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medbox.server.common.TraceContext;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.dto.auth.TokenResponse;
import com.medbox.server.service.AuthService;
import com.medbox.server.support.TestErrorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验收 07 清单地基第 4 项 {@code feat/backend-auth} 的 Web 层：接口通、错误码对、登录拦截生效。
 *
 * <p>只切片 {@code AuthController}（外加测试控制器，用来验证受保护接口的 40101），
 * 服务层与 JWT 全部打桩 —— **不连数据库**（CI 里没有 PostgreSQL）。
 */
@WebMvcTest(controllers = {AuthController.class, TestErrorController.class})
class AuthControllerTest {

    private static final String REGISTER_BODY =
            "{\"phone\":\"13800001234\",\"password\":\"123456\",\"role\":\"GUARDIAN\",\"name\":\"李四\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    /** 只为满足 AuthInterceptor 的装配（本切片不校验真实签名）。 */
    @MockitoBean
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("受保护接口未带 Authorization → 40101 / HTTP 401")
    void protectedPathWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/_test/ok"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("/auth/** 放行：登录不需要 token")
    void login() throws Exception {
        when(authService.login(any())).thenReturn(
                new TokenResponse("access-token", "refresh-token", "u-1001", "GUARDIAN", 7200L));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"13800001234\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.userId").value("u-1001"))
                .andExpect(jsonPath("$.data.role").value("GUARDIAN"))
                .andExpect(jsonPath("$.data.expiresIn").value(7200));
    }

    @Test
    @DisplayName("账号或密码错误 → 40102，不区分账号不存在与密码错误")
    void badCredentials() throws Exception {
        when(authService.login(any())).thenThrow(BizException.of(ErrorCode.BAD_CREDENTIALS));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"13800001234\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102))
                .andExpect(jsonPath("$.message").value("账号或密码错误"));
    }

    @Test
    @DisplayName("参数缺失 → 40001，提示取第一条字段错误")
    void validationFailed() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("account 不能为空"));
    }

    @Test
    @DisplayName("注册：X-Request-Id 原样传给服务层做幂等")
    void registerPassesRequestId() throws Exception {
        when(authService.register(any(), any())).thenReturn(
                new TokenResponse("access-token", "refresh-token", "u-1002", "GUARDIAN", 7200L));

        mockMvc.perform(post("/api/v1/auth/register")
                        .header(TraceContext.REQUEST_ID_HEADER, "req-123456")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("u-1002"));

        verify(authService).register(any(), eq("req-123456"));
    }

    @Test
    @DisplayName("刷新：返回新的一对令牌")
    void refresh() throws Exception {
        when(authService.refresh(any())).thenReturn(
                new TokenResponse("new-access", "new-refresh", "u-1001", "ELDER", 7200L));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("new-access"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh"));
    }
}

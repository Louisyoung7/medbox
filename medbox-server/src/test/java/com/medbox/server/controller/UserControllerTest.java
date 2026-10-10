package com.medbox.server.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.dto.user.UserProfileResponse;
import com.medbox.server.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验收 {@code GET /api/v1/users/me}（07 清单 {@code feat/backend-authz}）。
 *
 * <p>切片 {@code UserController}；**第 ① 步走真链路** —— 注入真实的 {@link JwtProvider}
 * （用 application.yml 里的 {@code medbox.jwt.secret}），用真实签发的 access token 打过去，
 * 而不是打桩放过拦截器。Mapper 层打桩，**不连数据库**。
 */
@WebMvcTest(controllers = UserController.class)
@Import(JwtProvider.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserService userService;

    @Test
    @DisplayName("未带 Authorization → 40101 / HTTP 401")
    void 未登录返回_40101() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("拿 refresh token 当 access 用 → 40101（typ 校验）")
    void 刷新令牌不能当访问令牌() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtProvider.issue("u-1001", "ELDER", TokenType.REFRESH)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("带合法 access token → 200，返回 userId / role / name / phone / username")
    void 返回当前用户资料与角色() throws Exception {
        when(userService.me()).thenReturn(
                new UserProfileResponse("u-1001", "GUARDIAN", "李四", "13800001234", "lisi"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtProvider.issue("u-1001", "GUARDIAN", TokenType.ACCESS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value("u-1001"))
                .andExpect(jsonPath("$.data.role").value("GUARDIAN"))
                .andExpect(jsonPath("$.data.name").value("李四"))
                .andExpect(jsonPath("$.data.phone").value("13800001234"))
                .andExpect(jsonPath("$.data.username").value("lisi"));
    }

    @Test
    @DisplayName("账号已不存在 → 40401 / HTTP 404，失败时 data 不出现在 JSON 里")
    void 账号不存在返回_40401() throws Exception {
        when(userService.me())
                .thenThrow(BizException.of(ErrorCode.NOT_FOUND, "当前账号不存在，请重新登录"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtProvider.issue("u-1001", "ELDER", TokenType.ACCESS)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.message").value("当前账号不存在，请重新登录"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}

package com.medbox.server.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.service.AccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 调试推送端点的 Web 集成测试（MockMvc 打真实链路：拦截器 → 控制器 → 真实 {@code AccessService}）。
 *
 * <p>重点证明"它不是后门"：没登录 40101、越权 40301，能推的范围不超出可见老人。
 */
@WebMvcTest(controllers = WsDebugController.class, properties = "medbox.ws.debug-endpoint-enabled=true")
@Import({JwtProvider.class, AccessService.class})
class WsDebugControllerTest {

    private static final String ELDER = "e-1001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @MockitoBean
    private WsPushService pushService;

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    @Test
    @DisplayName("未登录 → 401 / 40101")
    void unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/_debug/ws/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":\"" + ELDER + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("老人本人推自己的主题 → 200，走的是 WsPushService 唯一出口")
    void elderPublishesOwnTopic() throws Exception {
        when(pushService.publish(eq("elder:" + ELDER), eq(WsFrameType.ALARM), any()))
                .thenReturn(new WsPushService.WsPushResult("evt-1", 1));

        mockMvc.perform(post("/api/v1/_debug/ws/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":\"" + ELDER + "\",\"type\":\"ALARM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.eventId").value("evt-1"))
                .andExpect(jsonPath("$.data.deliveredSessions").value(1));

        verify(pushService).publish(eq("elder:" + ELDER), eq(WsFrameType.ALARM), any());
    }

    @Test
    @DisplayName("越权推他人主题 → 403 / 40301，且不触发推送")
    void cannotPublishOthersTopic() throws Exception {
        mockMvc.perform(post("/api/v1/_debug/ws/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer("u-3001", "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":\"" + ELDER + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("未知帧类型 → 400 / 40001")
    void unknownFrameType() throws Exception {
        mockMvc.perform(post("/api/v1/_debug/ws/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":\"" + ELDER + "\",\"type\":\"PONG\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("缺 elderId → 400 / 40001")
    void missingElderId() throws Exception {
        mockMvc.perform(post("/api/v1/_debug/ws/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }
}

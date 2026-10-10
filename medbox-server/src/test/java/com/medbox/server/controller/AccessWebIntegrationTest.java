package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.service.AccessService;
import com.medbox.server.support.TestAccessController;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 权限判定的 Web 集成测试：MockMvc 打**真实链路**
 * （{@code TestAccessController} → 真实 {@code AccessService} → 打桩的 Mapper），
 * 断言判定失败时统一响应结构与 HTTP 状态码一致。
 *
 * <p>token 由真实的 {@link JwtProvider} 签发，第 ① 步不绕过；**不连数据库**（CI 无 PostgreSQL）。
 */
@WebMvcTest(controllers = TestAccessController.class)
@Import({JwtProvider.class, AccessService.class})
class AccessWebIntegrationTest {

    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String STRANGER_ID = "u-3001";
    private static final String DEVICE_ID = "BOXA1001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @BeforeEach
    void setUp() {
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any())).thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    @Test
    @DisplayName("① 未带 Authorization → 40101 / HTTP 401")
    void 未登录返回_40101() throws Exception {
        mockMvc.perform(get("/api/v1/_test/access/plan").param("elderId", ELDER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("② 非监护人访问他人数据 → 40301 / HTTP 403，data 不出现")
    void 非监护人访问他人数据返回_40301() throws Exception {
        mockMvc.perform(get("/api/v1/_test/access/plan")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("③ PENDING 关系 → 40302 / HTTP 403")
    void PENDING关系返回_40302() throws Exception {
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.PENDING));

        mockMvc.perform(get("/api/v1/_test/access/plan-write")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302))
                .andExpect(jsonPath("$.message").value("监护关系未生效，请等待老人确认"));
    }

    @Test
    @DisplayName("④ ACTIVE 家属读计划 → 200，data = GUARDIAN")
    void 生效家属可读计划() throws Exception {
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE));

        mockMvc.perform(get("/api/v1/_test/access/plan")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("GUARDIAN"));
    }

    @Test
    @DisplayName("④ ACTIVE 护理改计划 → 40301")
    void 护理不可改计划() throws Exception {
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.NURSE, RelationStatus.ACTIVE));

        mockMvc.perform(get("/api/v1/_test/access/plan-write")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("②③ 老人本人读自己的计划 → 200，data = SELF")
    void 老人可读自己的计划() throws Exception {
        mockMvc.perform(get("/api/v1/_test/access/plan")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("SELF"));
    }

    @Test
    @DisplayName("④ 老人本人不可改计划 → 40301")
    void 老人不可改计划() throws Exception {
        mockMvc.perform(get("/api/v1/_test/access/plan-write")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("可见老人数量：老人 1 位、ACTIVE 监护 2 位")
    void 可见老人数量() throws Exception {
        when(guardianRelationMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any())).thenReturn(List.of(
                relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE),
                relation("u-1002", GUARDIAN_ID, RelationRole.NURSE, RelationStatus.ACTIVE)));

        mockMvc.perform(get("/api/v1/_test/access/visible")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));

        mockMvc.perform(get("/api/v1/_test/access/visible")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    @DisplayName("设备维度：resolver 未接入 → 50000 / HTTP 500")
    void 设备归属解析未接入返回_50000() throws Exception {
        mockMvc.perform(get("/api/v1/_test/access/device")
                        .param("deviceId", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000));
    }

    // —— 辅助方法 —— //

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private void givenRelation(GuardianRelation relation) {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any())).thenReturn(relation);
    }

    private static GuardianRelation relation(String elderId, String guardianId,
                                             RelationRole role, RelationStatus status) {
        GuardianRelation relation = new GuardianRelation();
        relation.setRelationId("r-1001");
        relation.setElderId(elderId);
        relation.setGuardianId(guardianId);
        relation.setRole(role);
        relation.setStatus(status);
        return relation;
    }

    private static User elder(String userId) {
        User user = new User();
        user.setUserId(userId);
        user.setRole(UserRole.ELDER);
        user.setName("张三");
        user.setPhone("13800001234");
        user.setUsername("zhangsan");
        return user;
    }
}

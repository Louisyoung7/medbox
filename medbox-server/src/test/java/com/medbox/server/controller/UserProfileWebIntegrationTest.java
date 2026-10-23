package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/users/{elderId}/profile} 的集成测试（07 清单 {@code feat/user-profile}，见文档 02 第 3 章）。
 *
 * <p>真实链路：MockMvc → {@code UserController} → 真实 {@code UserService} → 真实
 * {@code AccessService} → 打桩的 Mapper。覆盖「监护人可见」的全部判定分支：老人本人 /
 * ACTIVE 监护人可看，无关系 / REVOKED / 目标非老人 → 40301（防枚举），PENDING → 40302。
 *
 * <p><b>不连真实 PostgreSQL</b>：用内嵌 H2 替换 DataSource（Mapper 仍是 mock、不跑真实 SQL，
 * Flyway 已在测试任务里关闭）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileWebIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-user-profile-test")
                    .build();
        }
    }

    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String STRANGER_ID = "u-9999";

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
        // Mapper 被打桩后 MyBatis 没有 TableInfo，Lambda Wrapper 解析列名会炸 —— 先预热
        TableInfoWarmup.init(User.class, GuardianRelation.class);
        // 默认把「目标用户」当成真实老人（AccessService.isElder 与 UserService.findByUserId 都用 selectOne 查）
        when(userMapper.selectOne(any())).thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    @Test
    @DisplayName("未带 access token → 40101")
    void 未登录返回_40101() throws Exception {
        mockMvc.perform(get("/api/v1/users/{elderId}/profile", ELDER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("老人本人看自己 → 200，返回 userId / role / name / phone")
    void 老人本人看自己() throws Exception {
        mockMvc.perform(get("/api/v1/users/{elderId}/profile", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(ELDER_ID))
                .andExpect(jsonPath("$.data.role").value("ELDER"))
                .andExpect(jsonPath("$.data.name").value("张三"))
                .andExpect(jsonPath("$.data.phone").value("13800001234"));
    }

    @Test
    @DisplayName("老人访问他人资料 → 40301（只读本人）")
    void 老人访问他人返回_40301() throws Exception {
        mockMvc.perform(get("/api/v1/users/{elderId}/profile", "u-1002")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("监护人 ACTIVE 关系 → 200，可见老人基础信息")
    void 监护人生效关系可见() throws Exception {
        when(guardianRelationMapper.selectOne(any()))
                .thenReturn(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE));

        mockMvc.perform(get("/api/v1/users/{elderId}/profile", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(ELDER_ID))
                .andExpect(jsonPath("$.data.role").value("ELDER"))
                .andExpect(jsonPath("$.data.name").value("张三"));
    }

    @Test
    @DisplayName("无关系监护人 → 40301（防枚举）")
    void 无关系监护人返回_40301() throws Exception {
        when(guardianRelationMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(get("/api/v1/users/{elderId}/profile", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("监护关系 PENDING → 40302（老人尚未确认）")
    void 待确认关系返回_40302() throws Exception {
        when(guardianRelationMapper.selectOne(any()))
                .thenReturn(relation(ELDER_ID, GUARDIAN_ID, null, RelationStatus.PENDING));

        mockMvc.perform(get("/api/v1/users/{elderId}/profile", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    @Test
    @DisplayName("监护关系 REVOKED → 40301（与无关系同码，不泄漏曾有关系）")
    void 已解除关系返回_40301() throws Exception {
        when(guardianRelationMapper.selectOne(any()))
                .thenReturn(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.REVOKED));

        mockMvc.perform(get("/api/v1/users/{elderId}/profile", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("目标不是老人（普通监护人账号）→ 40301")
    void 目标非老人返回_40301() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(guardian(GUARDIAN_ID));

        mockMvc.perform(get("/api/v1/users/{elderId}/profile", GUARDIAN_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private static GuardianRelation relation(String elderId, String guardianId,
                                             RelationRole role, RelationStatus status) {
        GuardianRelation r = new GuardianRelation();
        r.setId(1L);
        r.setRelationId("r-1001");
        r.setElderId(elderId);
        r.setGuardianId(guardianId);
        r.setRole(role);
        r.setStatus(status);
        r.setCreatedAt(OffsetDateTime.parse("2026-10-21T00:00:00Z"));
        return r;
    }

    private static User elder(String userId) {
        User u = new User();
        u.setUserId(userId);
        u.setRole(UserRole.ELDER);
        u.setName("张三");
        u.setPhone("13800001234");
        return u;
    }

    private static User guardian(String userId) {
        User u = new User();
        u.setUserId(userId);
        u.setRole(UserRole.GUARDIAN);
        u.setName("李四");
        return u;
    }
}

package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.domain.enums.UserStatus;
import com.medbox.server.mapper.AdminCleanupMapper;
import com.medbox.server.mapper.AdminUserMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
 * 管理后台测试数据清理接口的集成测试（feat/backend-admin-cleanup，见文档 02 第 10 章）。
 *
 * <p>真实链路：MockMvc → AdminUserController → 真实 AdminUserService → 打桩的 Mapper（不跑真实 SQL）。
 * 鉴权走真实的 AuthInterceptor + AdminAuthInterceptor（JwtProvider 真实签发 token）。
 *
 * <p>不连真实 PostgreSQL：@Transactional 服务需要 PlatformTransactionManager，用内嵌 H2 替换 DataSource
 * （Mapper 仍是 mock、不跑真实 SQL，级联顺序用 InOrder 校验）；Flyway 在测试环境关闭，V11 种子迁移不执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserWebIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-admin-test")
                    .build();
        }
    }

    private static final String ADMIN_ID = "u-admin";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String TARGET_ID = "u-target";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private AdminUserMapper adminUserMapper;

    @MockitoBean
    private AdminCleanupMapper cleanupMapper;

    @BeforeEach
    void setUp() {
        // Mapper 被打桩后 MyBatis 不会为实体建表信息；purge 走 QueryWrapper，预热避免列名解析异常
        TableInfoWarmup.init(User.class);
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    // —— 鉴权护栏 —— //

    @Test
    @DisplayName("未带 Authorization → 40101 / HTTP 401")
    void 未登录返回_40101() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("非 ADMIN（GUARDIAN）→ 40301 / HTTP 403")
    void 非管理员返回_40301() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("ADMIN 列出用户 → 200，分页结构正确")
    void 管理员列出用户() throws Exception {
        User deleted = user(TARGET_ID, UserStatus.DELETED);
        User active = user("u-other", UserStatus.ACTIVE);
        when(adminUserMapper.countByFilter(any(), any(), any())).thenReturn(2L);
        when(adminUserMapper.selectByFilter(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(deleted, active));

        mockMvc.perform(get("/api/v1/admin/users").param("page", "1").param("size", "20")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.list[0].userId").value(TARGET_ID))
                .andExpect(jsonPath("$.data.list[0].status").value("DELETED"))
                .andExpect(jsonPath("$.data.list[1].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("非 ADMIN 清理 → 40301，且未进入业务（userMapper 未被查）")
    void 非管理员清理返回_40301() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/users/{userId}", TARGET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(userMapper, never()).selectOne(any(QueryWrapper.class));
    }

    // —— 清理护栏与级联 —— //

    @Test
    @DisplayName("ADMIN 清理不存在用户 → 40401")
    void 清理不存在返回_40401() throws Exception {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", TARGET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));

        verify(cleanupMapper, never()).deleteUser(anyString());
    }

    @Test
    @DisplayName("ADMIN 清理 ACTIVE 用户 → 40902，不级联")
    void 清理活跃账号返回_40902() throws Exception {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(user(TARGET_ID, UserStatus.ACTIVE));

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", TARGET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40902));

        verify(cleanupMapper, never()).deleteUser(anyString());
    }

    @Test
    @DisplayName("ADMIN 清理 DELETED 用户 → 200，按依赖顺序级联硬删（先子后父），user 最后删")
    void 清理已注销用户级联顺序正确() throws Exception {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(user(TARGET_ID, UserStatus.DELETED));

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", TARGET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        InOrder order = inOrder(cleanupMapper);
        order.verify(cleanupMapper).deleteMedPlanItems(TARGET_ID);
        order.verify(cleanupMapper).deleteMedPlans(TARGET_ID);
        order.verify(cleanupMapper).deleteGuardianRelations(TARGET_ID);
        order.verify(cleanupMapper).deleteDeviceEvents(TARGET_ID);
        order.verify(cleanupMapper).deleteDevices(TARGET_ID);
        order.verify(cleanupMapper).deleteMedicineStocks(TARGET_ID);
        order.verify(cleanupMapper).deleteMedicines(TARGET_ID);
        order.verify(cleanupMapper).deleteMedRecords(TARGET_ID);
        order.verify(cleanupMapper).deleteCaptures(TARGET_ID);
        order.verify(cleanupMapper).deleteEnvSamples(TARGET_ID);
        order.verify(cleanupMapper).deleteAlarms(TARGET_ID);
        order.verify(cleanupMapper).deleteAlarmSettings(TARGET_ID);
        order.verify(cleanupMapper).deleteRefreshTokens(TARGET_ID);
        order.verify(cleanupMapper).deleteUser(TARGET_ID);
        order.verifyNoMoreInteractions();
    }

    // —— 辅助 —— //

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private static User user(String userId, UserStatus status) {
        User u = new User();
        u.setUserId(userId);
        u.setRole(UserRole.ELDER);
        u.setName("测试用户");
        u.setStatus(status);
        u.setCreatedAt(OffsetDateTime.parse("2026-10-18T00:00:00Z"));
        return u;
    }
}

package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 监护关系写接口（{@code feat/guardian-relation}）的集成测试，见文档 02 第 3 章。
 *
 * <p>真实链路：MockMvc → {@code GuardianRelationController} → 真实
 * {@code GuardianRelationService} → 真实 {@code AccessService} → 打桩的 Mapper。
 * 覆盖 4 个接口的判定规则：申请的目标校验、PENDING→ACTIVE→REVOKED 状态流转、
 * 越权一律 40301（防枚举）、重复申请 40901（监护人统一单一角色，确认时不再指定子角色）。
 *
 * <p><b>不连真实 PostgreSQL</b>：apply 是 {@code @Transactional}，进入方法要开 JDBC 连接，
 * 用内嵌 H2 替换 DataSource（Mapper 仍是 mock、不跑真实 SQL）。Flyway 已在测试任务里关闭。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GuardianRelationWebIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-guardian-relation-test")
                    .build();
        }
    }

    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String STRANGER_ID = "u-9999";
    private static final String RELATION_ID = "r-1001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @MockitoBean
    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        // in(...) 会立即解析列名，Mapper 被打桩后没有 TableInfo —— 先预热
        TableInfoWarmup.init(User.class, GuardianRelation.class);
        // 默认把「目标用户」当成真实老人（requireElder 里的 isElder 用 selectOne 查）
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any())).thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    // ————————————————— 申请 POST /users/{elderId}/guardians —————————————————

    @Test
    @DisplayName("申请：未登录 → 40101")
    void 申请未登录返回_40101() throws Exception {
        mockMvc.perform(post("/api/v1/users/{elderId}/guardians", ELDER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("申请：老人本人（非 GUARDIAN）→ 40301")
    void 老人不可申请() throws Exception {
        mockMvc.perform(post("/api/v1/users/{elderId}/guardians", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(guardianRelationMapper, never()).insert(any(GuardianRelation.class));
    }

    @Test
    @DisplayName("申请：目标不是真实老人 → 40301")
    void 目标非老人返回_40301() throws Exception {
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/users/{elderId}/guardians", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(guardianRelationMapper, never()).insert(any(GuardianRelation.class));
    }

    @Test
    @DisplayName("申请：无既有关系 → 新建 PENDING，业务号由服务端生成")
    void 申请新建() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(null);
        when(guardianRelationMapper.nextRelationId()).thenReturn(RELATION_ID);
        when(guardianRelationMapper.insert(any(GuardianRelation.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/users/{elderId}/guardians", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relationId").value(RELATION_ID))
                .andExpect(jsonPath("$.data.elderId").value(ELDER_ID))
                .andExpect(jsonPath("$.data.guardianId").value(GUARDIAN_ID))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.role").doesNotExist());   // 新建未指定角色，role 为 null 被 non_null 省略
    }

    @Test
    @DisplayName("申请：已 PENDING → 40901（重复申请）")
    void 已待确认重复申请返回_40901() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(RELATION_ID, ELDER_ID, GUARDIAN_ID, RelationStatus.PENDING));

        mockMvc.perform(post("/api/v1/users/{elderId}/guardians", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("申请：已 ACTIVE → 40901（重复申请）")
    void 已生效重复申请返回_40901() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(RELATION_ID, ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/users/{elderId}/guardians", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("申请：已 REVOKED → 改回 PENDING 重新申请（清空 role）")
    void 已解除可重新申请() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(RELATION_ID, ELDER_ID, GUARDIAN_ID, RelationStatus.REVOKED));
        when(guardianRelationMapper.reactivatePending(any(), any())).thenReturn(1);

        mockMvc.perform(post("/api/v1/users/{elderId}/guardians", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.role").doesNotExist());   // 重新申请清空 role
    }

    // ————————————————— 待确认列表 GET /users/guardians/pending —————————————————

    @Test
    @DisplayName("待确认：监护人（非 ELDER）→ 40301")
    void 监护人不可看待确认列表() throws Exception {
        mockMvc.perform(get("/api/v1/users/guardians/pending")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("待确认：老人看自己的列表 → 200，带监护人姓名 + 手机")
    void 老人看待确认列表带姓名手机() throws Exception {
        when(guardianRelationMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(List.of(relation(RELATION_ID, ELDER_ID, GUARDIAN_ID, RelationStatus.PENDING)));
        when(userMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<User>>any()))
                .thenReturn(List.of(guardian(GUARDIAN_ID, "张三", "13800001234")));

        mockMvc.perform(get("/api/v1/users/guardians/pending")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].relationId").value(RELATION_ID))
                .andExpect(jsonPath("$.data[0].guardianName").value("张三"))
                .andExpect(jsonPath("$.data[0].guardianPhone").value("13800001234"));
    }

    // ————————————————— 确认 PATCH /users/guardians/{relationId}/accept —————————————————

    @Test
    @DisplayName("确认：关系查不到 → 40301（防枚举，不是 40401）")
    void 关系不存在返回_40301() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(null);

        mockMvc.perform(patch("/api/v1/users/guardians/{relationId}/accept", RELATION_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("确认：不是本人的关系 → 40301")
    void 确认他人关系返回_40301() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(RELATION_ID, "u-1002", GUARDIAN_ID, RelationStatus.PENDING));

        mockMvc.perform(patch("/api/v1/users/guardians/{relationId}/accept", RELATION_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("确认：关系已非 PENDING → 40901")
    void 确认非待确认关系返回_40901() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(RELATION_ID, ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE));

        mockMvc.perform(patch("/api/v1/users/guardians/{relationId}/accept", RELATION_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("确认：正常 PENDING → ACTIVE，角色生效")
    void 确认置生效() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(RELATION_ID, ELDER_ID, GUARDIAN_ID, RelationStatus.PENDING));
        when(guardianRelationMapper.updateById(any(GuardianRelation.class))).thenReturn(1);

        mockMvc.perform(patch("/api/v1/users/guardians/{relationId}/accept", RELATION_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    // ————————————————— 解除 DELETE /users/guardians/{relationId} —————————————————

    @Test
    @DisplayName("解除：非关系任一方 → 40301")
    void 陌生人不可解除() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(RELATION_ID, ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE));

        mockMvc.perform(delete("/api/v1/users/guardians/{relationId}", RELATION_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(guardianRelationMapper, never()).updateById(any(GuardianRelation.class));
    }

    @Test
    @DisplayName("解除：关系任一方（监护人）→ 200，软删置 REVOKED")
    void 任一方可解除() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(RELATION_ID, ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE));
        when(guardianRelationMapper.updateById(any(GuardianRelation.class))).thenReturn(1);

        mockMvc.perform(delete("/api/v1/users/guardians/{relationId}", RELATION_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private static GuardianRelation relation(String relationId, String elderId, String guardianId, RelationStatus status) {
        GuardianRelation r = new GuardianRelation();
        r.setId(1L);
        r.setRelationId(relationId);
        r.setElderId(elderId);
        r.setGuardianId(guardianId);
        r.setStatus(status);
        r.setCreatedAt(OffsetDateTime.parse("2026-10-21T00:00:00Z"));
        return r;
    }

    private static User elder(String userId) {
        User u = new User();
        u.setUserId(userId);
        u.setRole(UserRole.ELDER);
        u.setName("张三");
        return u;
    }

    private static User guardian(String userId, String name, String phone) {
        User u = new User();
        u.setUserId(userId);
        u.setRole(UserRole.GUARDIAN);
        u.setName(name);
        u.setPhone(phone);
        return u;
    }
}

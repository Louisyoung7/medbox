package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.time.OffsetDateTime;
import java.util.List;
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
 * 依从性统计集成测试（{@code feat/record-report}，见文档 02 第 7 章、07）。
 *
 * <p>真实链路：MockMvc → {@code AdherenceController} → 真实 {@code AdherenceService} →
 * 真实 {@code AccessService} → 打桩的 Mapper（不跑真实 SQL，用内嵌 H2 顶替 DataSource）。
 * 覆盖：统计口径（按时率 / 漏服 / 分桶）、周期非法 40001、权限矩阵（老人本人 200 / 陌生人 40301 / PENDING 40302）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdherenceWebIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test")
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
    private MedRecordMapper medRecordMapper;
    @MockitoBean
    private AlarmMapper alarmMapper;
    @MockitoBean
    private DeviceMapper deviceMapper;
    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(MedRecord.class, Alarm.class, Device.class, User.class, GuardianRelation.class);
        // AccessService.isElder 用 userMapper 校验目标是否老人：任何查询都返回一位 ELDER
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    @Test
    @DisplayName("依从性：FAMILY 监护人 → 200，按时率=0.7、漏服=3、分桶非空")
    void 监护人返回统计() throws Exception {
        givenGuardianGuards(ELDER_ID);
        // compute 内调用顺序：total / onTime / late（三次 selectCount），然后 records / missAlarms（两次 selectList）
        when(medRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(10L, 7L, 2L);
        when(alarmMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        when(medRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(alarmMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/adherence").param("elderId", ELDER_ID).param("period", "week")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.elderId").value(ELDER_ID))
                .andExpect(jsonPath("$.data.period").value("week"))
                .andExpect(jsonPath("$.data.totalDoses").value(10))
                .andExpect(jsonPath("$.data.onTimeCount").value(7))
                .andExpect(jsonPath("$.data.lateCount").value(2))
                .andExpect(jsonPath("$.data.unjudgedCount").value(1))
                .andExpect(jsonPath("$.data.onTimeRate").value(0.7))
                .andExpect(jsonPath("$.data.missCount").value(3))
                .andExpect(jsonPath("$.data.dailyBreakdown").isArray())
                .andExpect(jsonPath("$.data.dailyBreakdown.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(7)));
    }

    @Test
    @DisplayName("依从性：老人本人（SELF）→ 200，可见自己统计")
    void 老人本人返回() throws Exception {
        when(medRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L, 0L, 0L);
        when(alarmMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(medRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(alarmMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/adherence").param("elderId", ELDER_ID).param("period", "day")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalDoses").value(0))
                .andExpect(jsonPath("$.data.onTimeRate").value(0.0));
    }

    @Test
    @DisplayName("依从性：陌生人（无监护关系）→ 40301")
    void 陌生人返回_40301() throws Exception {
        when(medRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        mockMvc.perform(get("/api/v1/adherence").param("elderId", ELDER_ID).param("period", "day")
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("依从性：监护关系 PENDING → 40302")
    void 监护Pending返回_40302() throws Exception {
        givenGuardianGuardsPending(ELDER_ID);
        when(medRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        mockMvc.perform(get("/api/v1/adherence").param("elderId", ELDER_ID).param("period", "day")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    @Test
    @DisplayName("依从性：period 非法（year）→ 40001")
    void 周期非法返回_40001() throws Exception {
        givenGuardianGuards(ELDER_ID);

        mockMvc.perform(get("/api/v1/adherence").param("elderId", ELDER_ID).param("period", "year")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private void givenGuardianGuards(String... elderIds) {
        List<GuardianRelation> relations = java.util.Arrays.stream(elderIds)
                .map(id -> relation(id, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE))
                .toList();
        when(guardianRelationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(relations);
        when(guardianRelationMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(relations.isEmpty() ? null : relations.get(0));
    }

    private void givenGuardianGuardsPending(String... elderIds) {
        List<GuardianRelation> relations = java.util.Arrays.stream(elderIds)
                .map(id -> relation(id, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.PENDING))
                .toList();
        when(guardianRelationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(relations);
        when(guardianRelationMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(relations.isEmpty() ? null : relations.get(0));
    }

    private static GuardianRelation relation(String elderId, String guardianId,
                                            RelationRole role, RelationStatus status) {
        GuardianRelation r = new GuardianRelation();
        r.setRelationId("r-1001");
        r.setElderId(elderId);
        r.setGuardianId(guardianId);
        r.setRole(role);
        r.setStatus(status);
        return r;
    }

    private static User elder(String userId) {
        User u = new User();
        u.setUserId(userId);
        u.setRole(UserRole.ELDER);
        u.setName("张三");
        return u;
    }
}

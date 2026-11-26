package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.mqtt.DownlinkPublisher;
import com.medbox.server.mqtt.PendingAckRegistry;
import com.medbox.server.mqtt.ScheduleDownlinkPayload;
import com.medbox.server.support.TableInfoWarmup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
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
 * {@code POST /api/v1/plans/{planId}/sync} 的**集成测试**（{@code feat/backend-plan-sync}，见文档 02d）。
 *
 * <p>完整链路：MockMvc → {@code PlanController} → 真实 {@code PlanService.manualSync} →
 * 真实 {@code AccessService} → 事务提交后 {@code MqttPlanScheduleSync} → 打桩的 MQTT 下行发布器。
 * 验证"点一次发一次、连点不去重、状态不闪回 PENDING、越权 / 不存在 40301"。
 *
 * <p>嵌入式 H2 仅用于事务开连接，Mapper 全部打桩，不跑真实 SQL（CI 无 PostgreSQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanSyncWebIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test-plan-sync")
                    .build();
        }
    }

    private static final String PLAN_ID = "p-3301";
    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String DEVICE_ID = "BOXA1001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private MedPlanMapper medPlanMapper;

    @MockitoBean
    private DeviceMapper deviceMapper;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @MockitoBean
    private DownlinkPublisher downlinkPublisher;

    @MockitoBean
    private PendingAckRegistry pendingAckRegistry;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(MedPlan.class, Device.class, User.class, GuardianRelation.class);
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any()))
                .thenReturn(elder(ELDER_ID));
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation());
        when(medPlanMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<MedPlan>>any()))
                .thenReturn(plan());
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(device());
        when(downlinkPublisher.publish(eq(DEVICE_ID), eq(DownlinkPublisher.SUB_TOPIC_SCHEDULE),
                any(), eq(true))).thenAnswer(inv -> "msg-" + inv.getArgument(0).hashCode());
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("监护人手动同步：200，状态保持 SYNCED_TO_DEVICE（不闪回 PENDING），下发 UPSERT 一次")
    void 手动同步成功状态不变() throws Exception {
        mockMvc.perform(post("/api/v1/plans/{planId}/sync", PLAN_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.planId").value(PLAN_ID))
                .andExpect(jsonPath("$.data.syncState").value("SYNCED_TO_DEVICE"));

        ArgumentCaptor<ScheduleDownlinkPayload> payload = ArgumentCaptor.forClass(ScheduleDownlinkPayload.class);
        verify(downlinkPublisher).publish(eq(DEVICE_ID), eq(DownlinkPublisher.SUB_TOPIC_SCHEDULE),
                payload.capture(), eq(true));
        org.junit.jupiter.api.Assertions.assertEquals("UPSERT", payload.getValue().getOp());
        org.junit.jupiter.api.Assertions.assertEquals(PLAN_ID, payload.getValue().getPlanId());
    }

    @Test
    @DisplayName("连点 3 次：产生 3 次独立下发（不去重）")
    void 连点三次不去重() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/plans/{planId}/sync", PLAN_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        verify(downlinkPublisher, times(3)).publish(eq(DEVICE_ID),
                eq(DownlinkPublisher.SUB_TOPIC_SCHEDULE), any(), eq(true));
    }

    @Test
    @DisplayName("老人本人 → 40301（WRITE_PLAN 仅 GUARDIAN）")
    void 老人本人_40301() throws Exception {
        mockMvc.perform(post("/api/v1/plans/{planId}/sync", PLAN_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("计划不存在 → 40301（防枚举）")
    void 计划不存在_40301() throws Exception {
        when(medPlanMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<MedPlan>>any()))
                .thenReturn(null);

        mockMvc.perform(post("/api/v1/plans/{planId}/sync", "NOT-EXIST")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("越权（无监护关系）→ 40301")
    void 越权_40301() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(null);

        mockMvc.perform(post("/api/v1/plans/{planId}/sync", PLAN_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("未登录 → 40101")
    void 未登录_40101() throws Exception {
        mockMvc.perform(post("/api/v1/plans/{planId}/sync", PLAN_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("无 MAIN 设备：返回 200、状态不变，不下发（安全降级）")
    void 无设备安全降级() throws Exception {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(null);

        mockMvc.perform(post("/api/v1/plans/{planId}/sync", PLAN_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncState").value("SYNCED_TO_DEVICE"));

        verify(downlinkPublisher, times(0)).publish(any(), any(), any(), eq(true));
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private static GuardianRelation relation() {
        GuardianRelation relation = new GuardianRelation();
        relation.setRelationId("r-1001");
        relation.setElderId(ELDER_ID);
        relation.setGuardianId(GUARDIAN_ID);
        relation.setStatus(RelationStatus.ACTIVE);
        return relation;
    }

    private static User elder(String userId) {
        User user = new User();
        user.setUserId(userId);
        user.setRole(UserRole.ELDER);
        user.setName("张三");
        return user;
    }

    private static Device device() {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setElderId(ELDER_ID);
        device.setProductKey("medbox-box");
        return device;
    }

    private static MedPlan plan() {
        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(ELDER_ID);
        plan.setSyncState("SYNCED_TO_DEVICE");
        plan.setStatus("ENABLED");
        return plan;
    }
}

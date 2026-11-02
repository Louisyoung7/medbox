package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.User;
import com.medbox.server.mapper.AdminDashboardMapper;
import com.medbox.server.mapper.DeviceDashboardRow;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.service.DeviceTimezoneResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medbox.server.service.PlanSlotResolver;
import com.medbox.server.support.TableInfoWarmup;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 管理后台监控看板接口集成测试（feat/backend-admin-dashboard，见文档 02 §10.4）。
 *
 * <p>真实链路：MockMvc → AdminDashboardController → 真实 AdminDashboardService → 打桩的 Mapper / 解析器
 * （不跑真实 SQL）。鉴权走真实的 AuthInterceptor + AdminAuthInterceptor（JwtProvider 真实签发 token）。
 *
 * <p>不连真实 PostgreSQL：@Transactional 服务需要 PlatformTransactionManager，用内嵌 H2 替换 DataSource
 * （Mapper 仍是 mock、不跑真实 SQL）；Flyway 在测试环境关闭，V11 种子迁移不执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminDashboardWebIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-dashboard-test")
                    .build();
        }
    }

    private static final String ADMIN_ID = "u-admin";
    private static final String GUARDIAN_ID = "u-2001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private AdminDashboardMapper dashboardMapper;

    @MockitoBean
    private MedPlanMapper medPlanMapper;

    @MockitoBean
    private PlanSlotResolver planSlotResolver;

    @MockitoBean
    private DeviceTimezoneResolver timezoneResolver;

    @BeforeEach
    void setUp() {
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
        mockMvc.perform(get("/api/v1/admin/dashboard/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("非 ADMIN（GUARDIAN）→ 40301 / HTTP 403")
    void 非管理员返回_40301() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/overview")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // —— 10.4.1 总览 —— //

    @Test
    @DisplayName("ADMIN 总览 → 200，设备/今日服药/老人计划数结构正确")
    void 管理员总览() throws Exception {
        when(medPlanMapper.selectDueForMissScan()).thenReturn(List.of());
        when(dashboardMapper.countDevices()).thenReturn(5L);
        when(dashboardMapper.countDevicesByState("ONLINE")).thenReturn(3L);
        when(dashboardMapper.countDevicesByState("OFFLINE")).thenReturn(2L);
        when(dashboardMapper.countElders()).thenReturn(4L);
        when(dashboardMapper.countEnabledPlans()).thenReturn(3L);

        mockMvc.perform(get("/api/v1/admin/dashboard/overview")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.devices.total").value(5))
                .andExpect(jsonPath("$.data.devices.online").value(3))
                .andExpect(jsonPath("$.data.devices.offline").value(2))
                .andExpect(jsonPath("$.data.devices.mqttConnected").value(3))
                .andExpect(jsonPath("$.data.todayDose.totalSlots").value(0))
                .andExpect(jsonPath("$.data.todayDose.taken").value(0))
                .andExpect(jsonPath("$.data.todayDose.missed").value(0))
                .andExpect(jsonPath("$.data.todayDose.pending").value(0))
                .andExpect(jsonPath("$.data.elderCount").value(4))
                .andExpect(jsonPath("$.data.planCount").value(3));
    }

    // —— 10.4.2 设备网络状态 —— //

    @Test
    @DisplayName("ADMIN 设备列表 → 200，行结构与字段正确")
    void 管理员设备列表() throws Exception {
        DeviceDashboardRow row = new DeviceDashboardRow();
        row.setDeviceId("BOXA1001");
        row.setElderId("e-1001");
        row.setElderName("张三");
        row.setOnlineState("ONLINE");
        row.setLastHeartbeat(OffsetDateTime.parse("2026-11-02T01:00:00Z"));
        when(dashboardMapper.selectRecentDevices(20)).thenReturn(List.of(row));

        mockMvc.perform(get("/api/v1/admin/dashboard/devices")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].deviceId").value("BOXA1001"))
                .andExpect(jsonPath("$.data[0].elderId").value("e-1001"))
                .andExpect(jsonPath("$.data[0].elderName").value("张三"))
                .andExpect(jsonPath("$.data[0].status").value("ONLINE"));
    }

    // —— 10.4.3 服药依从率 —— //

    @Test
    @DisplayName("ADMIN 依从率(week) → 200，趋势 7 点、按老人 top10 正确")
    void 管理员依从率周() throws Exception {
        MedPlan plan = new MedPlan();
        plan.setElderId("e-1001");
        plan.setTimes(List.of("08:00"));
        when(medPlanMapper.selectDueForMissScan()).thenReturn(List.of(plan));
        when(timezoneResolver.resolve("e-1001")).thenReturn(ZoneId.of("Asia/Shanghai"));
        // 每次展开返回 2 个槽位；一周 7 天 → 每日 expected=2，按老人合计 14
        when(planSlotResolver.expandSlots(any(), any(), any(), any()))
                .thenReturn(List.of(OffsetDateTime.now(), OffsetDateTime.now()));
        when(dashboardMapper.countTakenByDay(any(), any())).thenReturn(List.of());
        when(dashboardMapper.countMissedByDay(any(), any())).thenReturn(List.of());
        when(dashboardMapper.countTakenByElder(any(), any())).thenReturn(List.of());
        when(dashboardMapper.countMissedByElder(any(), any())).thenReturn(List.of());
        when(dashboardMapper.countMissedByHour(any(), any())).thenReturn(List.of());

        // 周粒度下趋势按 ISO 周归并桶（7 天通常跨 1~2 桶），故不校验具体桶数，只校验合计与按老人维度
        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(get("/api/v1/admin/dashboard/adherence")
                        .param("granularity", "week")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.byElder.length()").value(1))
                .andExpect(jsonPath("$.data.byElder[0].elderId").value("e-1001"))
                .andExpect(jsonPath("$.data.byElder[0].totalSlots").value(14))
                .andExpect(jsonPath("$.data.byElder[0].taken").value(0))
                .andExpect(jsonPath("$.data.missByHour.length()").value(0))
                .andReturn();

        // 趋势各桶 totalSlots 合计须等于全窗口期望槽（7 天 × 每次展开 2 槽 = 14）
        JsonNode data = new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("data");
        long trendTotal = 0;
        for (JsonNode point : data.path("trend")) {
            trendTotal += point.path("totalSlots").asLong();
        }
        assertEquals(14, trendTotal, "趋势桶 totalSlots 合计应等于全窗口期望槽");
    }

    @Test
    @DisplayName("ADMIN 依从率 非法 granularity → 40001")
    void 依从率非法参数() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard/adherence")
                        .param("granularity", "year")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // —— 10.4.4 MQTT 消息流 —— //

    @Test
    @DisplayName("ADMIN 消息流 → 200，上下行计数正确")
    void 管理员消息流() throws Exception {
        when(dashboardMapper.countMqttMessages()).thenReturn(100L);
        when(dashboardMapper.countMqttMessagesSince(any())).thenReturn(5L);
        when(dashboardMapper.countDeviceCommands()).thenReturn(50L);
        when(dashboardMapper.countDeviceCommandsSince(any())).thenReturn(2L);

        mockMvc.perform(get("/api/v1/admin/dashboard/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.upTotal").value(100))
                .andExpect(jsonPath("$.data.upLastMinute").value(5))
                .andExpect(jsonPath("$.data.downTotal").value(50))
                .andExpect(jsonPath("$.data.downLastMinute").value(2));
    }

    // —— 辅助 —— //

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }
}

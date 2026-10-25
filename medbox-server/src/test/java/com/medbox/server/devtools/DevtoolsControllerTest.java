package com.medbox.server.devtools;

import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.config.DeviceProperties;
import com.medbox.server.config.DevtoolsConfig;
import com.medbox.server.common.security.PasswordHasher;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.MedicineStock;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.User;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.MedicineStockMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.PlanReminderMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.mqtt.MqttTransport;
import com.medbox.server.service.PlanReminderScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
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
 * 联调辅助端点的 Web 集成测试（MockMvc 打真实链路：拦截器 → 控制器 → Service 编排层）。
 *
 * <p>Mapper 全部打桩（不跑真实 SQL，符合 AGENTS.md 3.1 的"CI 无 Postgres"约束），重点验证：
 * 门禁生效（devtools.enabled 控制装配）、免登录即可调用、种子幂等与 reset、模拟上报的 topic 拼装与 payload 透传、MQTT 未连接时的错误码。
 */
@WebMvcTest(controllers = DevtoolsController.class, properties = "medbox.devtools.enabled=true")
@Import({DevtoolsConfig.class, JwtProvider.class, PasswordHasher.class,
        DevtoolsSeedService.class, DevtoolsMqttSimService.class})
class DevtoolsControllerTest {

    private static final String GUARDIAN = "u-debug-guardian";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @MockitoBean
    private DeviceMapper deviceMapper;

    @MockitoBean
    private MedicineMapper medicineMapper;

    @MockitoBean
    private MedicineStockMapper medicineStockMapper;

    @MockitoBean
    private MedPlanMapper medPlanMapper;

    @MockitoBean
    private MedPlanItemMapper medPlanItemMapper;

    @MockitoBean
    private PlanReminderMapper planReminderMapper;

    @MockitoBean
    private PlanReminderScanner planReminderScanner;

    @MockitoBean
    private MqttTransport mqttTransport;

    @MockitoBean
    private DeviceProperties deviceProperties;

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("开启 devtools 时 _debug 免登录：无 token 也能播种")
    void noLoginRequired() throws Exception {
        when(userMapper.selectCount(any())).thenReturn(0L);

        mockMvc.perform(post("/api/v1/_debug/seed").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(true))
                .andExpect(jsonPath("$.data.guardianToken").exists())
                .andExpect(jsonPath("$.data.elderToken").exists());
    }

    @Test
    @DisplayName("首次播种：落全量数据并返回监护人 token")
    void seedFresh() throws Exception {
        when(userMapper.selectCount(any())).thenReturn(0L);

        mockMvc.perform(post("/api/v1/_debug/seed")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(true))
                .andExpect(jsonPath("$.data.guardianUserId").value("u-debug-guardian"))
                .andExpect(jsonPath("$.data.elderUserId").value("e-1001"))
                .andExpect(jsonPath("$.data.elderPhone").value("13800008888"))
                .andExpect(jsonPath("$.data.deviceId").value("BOXA-DEBUG-01"))
                .andExpect(jsonPath("$.data.planId").value("p-3301"))
                .andExpect(jsonPath("$.data.planItemId").value("pi-9001"))
                .andExpect(jsonPath("$.data.guardianToken").exists())
                .andExpect(jsonPath("$.data.elderToken").exists());

        // 监护人 + 老人 = 2 次；关系 1 次；设备 1 主控 + 3 传感器 = 4 次；
        // 药品 2 次；库存 1 次；计划 1 次；计划项 1 次
        verify(userMapper, times(2)).insert(any(User.class));
        verify(guardianRelationMapper).insert(any(GuardianRelation.class));
        verify(deviceMapper, times(4)).insert(any(Device.class));
        verify(medicineMapper, times(2)).insert(any(Medicine.class));
        verify(medicineStockMapper).insert(any(MedicineStock.class));
        verify(medPlanMapper).insert(any(MedPlan.class));
        verify(medPlanItemMapper).insert(any(MedPlanItem.class));
    }

    @Test
    @DisplayName("监护人缺失但有残留数据（如设备/计划）：自愈——先清残留再插入")
    void seedHealsPartialLeftover() throws Exception {
        when(userMapper.selectCount(any())).thenReturn(0L); // 监护人不在，但设备/计划等可能残留

        mockMvc.perform(post("/api/v1/_debug/seed").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(true))
                .andExpect(jsonPath("$.data.guardianToken").exists());

        // 先按固定 ID 清掉残留（设备被删两次：主控 + 传感器），再整批插入
        verify(deviceMapper, times(2)).delete(any(Wrapper.class));
        verify(userMapper, times(2)).insert(any(User.class));
        verify(deviceMapper, times(4)).insert(any(Device.class));
    }

    @Test
    @DisplayName("重复播种：幂等跳过，不重复写入")
    void seedIdempotent() throws Exception {
        when(userMapper.selectCount(any())).thenReturn(1L);

        mockMvc.perform(post("/api/v1/_debug/seed")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(false))
                .andExpect(jsonPath("$.data.guardianToken").exists());

        verify(userMapper, never()).insert(any(User.class));
        verify(deviceMapper, never()).insert(any(Device.class));
        verify(medPlanMapper, never()).insert(any(MedPlan.class));
    }

    @Test
    @DisplayName("reset=true：先清后种")
    void seedReset() throws Exception {
        when(userMapper.selectCount(any())).thenReturn(1L);
        when(deviceProperties.getDefaultProductKey()).thenReturn("medbox-box");

        mockMvc.perform(post("/api/v1/_debug/seed?reset=true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(true));

        // 清理阶段按外键逆序删除，再重新全量插入
        verify(userMapper).delete(any(Wrapper.class));
        verify(userMapper, times(2)).insert(any(User.class));
    }

    @Test
    @DisplayName("模拟上报：topic 拼装与 payload 透传正确")
    void mqttPublishHappy() throws Exception {
        when(mqttTransport.isConnected()).thenReturn(true);
        when(deviceProperties.getDefaultProductKey()).thenReturn("medbox-box");
        String payload = "{\"msgId\":\"BOXA-DEBUG-01-000123\",\"ts\":1759474500000,\"planId\":\"p-3301\","
                + "\"planItemId\":\"pi-9001\",\"medicineId\":\"m-205\",\"actualDose\":\"1\",\"unit\":\"片\","
                + "\"wrongDrug\":false,\"onTime\":true,\"confidence\":0.93,\"source\":\"CAMERA\"}";

        mockMvc.perform(post("/api/v1/_debug/mqtt/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"BOXA-DEBUG-01\",\"subTopic\":\"event/dispense\",\"payload\":" + escape(payload) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        var topicCaptor = forClass(String.class);
        var bodyCaptor = forClass(byte[].class);
        verify(mqttTransport).publish(topicCaptor.capture(), bodyCaptor.capture(), eq(1), eq(false));
        Assertions.assertEquals("medbox/medbox-box/BOXA-DEBUG-01/up/event/dispense", topicCaptor.getValue());
        Assertions.assertEquals(payload, new String(bodyCaptor.getValue()));
    }

    @Test
    @DisplayName("模拟上报：MQTT 未连接 → 500 / 50000")
    void mqttNotConnected() throws Exception {
        when(mqttTransport.isConnected()).thenReturn(false);
        when(deviceProperties.getDefaultProductKey()).thenReturn("medbox-box");

        mockMvc.perform(post("/api/v1/_debug/mqtt/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"BOXA-DEBUG-01\",\"subTopic\":\"event/dispense\",\"payload\":\"{}\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000));
    }

    @Test
    @DisplayName("模拟上报：缺 deviceId → 400 / 40001")
    void mqttMissingDeviceId() throws Exception {
        mockMvc.perform(post("/api/v1/_debug/mqtt/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subTopic\":\"event/dispense\",\"payload\":\"{}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("手动触发服药提醒扫描：调用扫描器并以 ScanSummary 返回")
    void triggerPlanReminder() throws Exception {
        when(planReminderScanner.scan(any())).thenReturn(
                new PlanReminderScanner.ScanSummary(1, 1, 0, 0, 0, 0));

        mockMvc.perform(post("/api/v1/_debug/plan-reminder/trigger")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pushed").value(1));

        verify(planReminderScanner).scan(any());
    }

    @Test
    @DisplayName("手动触发可带 now 参数：解析 ISO 时刻后调用扫描器")
    void triggerPlanReminderWithNow() throws Exception {
        when(planReminderScanner.scan(any())).thenReturn(
                new PlanReminderScanner.ScanSummary(0, 0, 0, 0, 0, 0));

        mockMvc.perform(post("/api/v1/_debug/plan-reminder/trigger?now=2026-10-25T06:00:00Z")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(planReminderScanner).scan(any());
    }

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private static String escape(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

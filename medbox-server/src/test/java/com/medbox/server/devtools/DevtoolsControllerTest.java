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
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.mqtt.MqttTransport;
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
 * 门禁生效、登录强制、种子幂等与 reset、模拟上报的 topic 拼装与 payload 透传、MQTT 未连接时的错误码。
 */
@WebMvcTest(controllers = DevtoolsController.class, properties = "medbox.devtools.enabled=true")
@Import({DevtoolsConfig.class, JwtProvider.class, PasswordHasher.class,
        DevtoolsSeedService.class, DevtoolsMqttSimService.class})
class DevtoolsControllerTest {

    private static final String GUARDIAN = "u-1001";

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
    private MqttTransport mqttTransport;

    @MockitoBean
    private DeviceProperties deviceProperties;

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("未登录 → 401 / 40101")
    void unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/_debug/seed").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
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
                .andExpect(jsonPath("$.data.guardianUserId").value("u-1001"))
                .andExpect(jsonPath("$.data.elderUserId").value("e-1001"))
                .andExpect(jsonPath("$.data.deviceId").value("BOXA1001"))
                .andExpect(jsonPath("$.data.planId").value("p-3301"))
                .andExpect(jsonPath("$.data.planItemId").value("pi-9001"))
                .andExpect(jsonPath("$.data.guardianToken").exists());

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
        String payload = "{\"msgId\":\"BOXA1001-000123\",\"ts\":1759474500000,\"planId\":\"p-3301\","
                + "\"planItemId\":\"pi-9001\",\"medicineId\":\"m-205\",\"actualDose\":\"1\",\"unit\":\"片\","
                + "\"wrongDrug\":false,\"onTime\":true,\"confidence\":0.93,\"source\":\"CAMERA\"}";

        mockMvc.perform(post("/api/v1/_debug/mqtt/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"BOXA1001\",\"subTopic\":\"event/dispense\",\"payload\":" + escape(payload) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        var topicCaptor = forClass(String.class);
        var bodyCaptor = forClass(byte[].class);
        verify(mqttTransport).publish(topicCaptor.capture(), bodyCaptor.capture(), eq(1), eq(false));
        Assertions.assertEquals("medbox/medbox-box/BOXA1001/up/event/dispense", topicCaptor.getValue());
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
                        .content("{\"deviceId\":\"BOXA1001\",\"subTopic\":\"event/dispense\",\"payload\":\"{}\"}"))
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

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private static String escape(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

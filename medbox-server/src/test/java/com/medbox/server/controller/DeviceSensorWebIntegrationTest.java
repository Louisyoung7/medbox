package com.medbox.server.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.DeviceMapper;
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
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /devices/{deviceId}/sensors} 的**集成测试**（{@code feat/device-sensor}，
 * 见文档 02 第 4 章 / 06 的 2.3）。
 *
 * <p>完整链路：MockMvc → {@code DeviceController} → 真实 {@code DeviceService}
 * → 真实 {@code AccessService} / {@code DeviceOwnerResolverImpl} → 打桩的 Mapper。
 * 权限判定（40301 防枚举口径）与"只列本主控子设备"都在这里验。
 *
 * <p><b>不连真实数据库</b>：CI 里没有 PostgreSQL，Mapper 全部打桩；{@code @Primary} 嵌入式 H2
 * 仅为可能触达的事务开连接（与 {@code DeviceCoreWebIntegrationTest} 同一套做法），不跑真实 SQL。
 *
 * <p>心跳消费与 180s 离线判定的链路归 {@code SensorStatusServiceTest} /
 * {@code SensorHeartbeatUpMessageHandlerTest}（MQTT 装配在测试期整体不建，handler 逻辑由纯单测覆盖）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceSensorWebIntegrationTest {

    /** 嵌入式 H2 替换 PostgreSQL DataSource：CI 无 Postgres，事务可开、不跑真实 SQL。 */
    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test-sensor")
                    .build();
        }
    }

    private static final String ELDER_ID = "u-1001";
    private static final String OTHER_ELDER_ID = "u-1002";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String DEVICE_ID = "BOXA1001";
    private static final String SENSOR_TH = "BOXA1001-S-TH01";
    private static final String SENSOR_LX = "BOXA1001-S-LX01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private DeviceMapper deviceMapper;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class, User.class, GuardianRelation.class);
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any()))
                .thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("监护人读子设备列表：返回各子设备的类型 / 在线状态 / 最后心跳")
    void 监护人读子设备列表() throws Exception {
        givenActiveRelation();
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));
        when(deviceMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(List.of(
                        sensor(SENSOR_TH, DEVICE_ID, ELDER_ID, OnlineState.ONLINE),
                        sensor(SENSOR_LX, DEVICE_ID, ELDER_ID, OnlineState.OFFLINE)));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/sensors", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].deviceId").value(SENSOR_TH))
                .andExpect(jsonPath("$.data[0].deviceType").value("TEMP_HUMI"))
                .andExpect(jsonPath("$.data[0].onlineState").value("ONLINE"))
                .andExpect(jsonPath("$.data[0].lastHeartbeat").value("2026-10-13T01:00:00Z"))
                .andExpect(jsonPath("$.data[1].deviceId").value(SENSOR_LX))
                .andExpect(jsonPath("$.data[1].onlineState").value("OFFLINE"));
    }

    @Test
    @DisplayName("老人本人可读自己药箱的子设备列表")
    void 老人本人可读() throws Exception {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));
        when(deviceMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(List.of(sensor(SENSOR_TH, DEVICE_ID, ELDER_ID, OnlineState.ONLINE)));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/sensors", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("无子设备 → 空数组（不是 404）")
    void 无子设备返回空列表() throws Exception {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));
        when(deviceMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/devices/{deviceId}/sensors", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("未登录 → 40101")
    void 未登录返回_40101() throws Exception {
        mockMvc.perform(get("/api/v1/devices/{deviceId}/sensors", DEVICE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("设备不存在 → 40301（与越权同码，防设备编号枚举）")
    void 设备不存在返回_40301() throws Exception {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(null);

        mockMvc.perform(get("/api/v1/devices/{deviceId}/sensors", "NOT-EXIST")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("传子设备编号 → 40301（deviceId 只接受药箱主控）")
    void 传子设备编号返回_40301() throws Exception {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(sensor(SENSOR_TH, DEVICE_ID, ELDER_ID, OnlineState.ONLINE));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/sensors", SENSOR_TH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("越权：访问他人药箱的子设备 → 40301")
    void 越权访问返回_40301() throws Exception {
        // 刻意不建到 OTHER_ELDER 的监护关系
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, OTHER_ELDER_ID));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/sensors", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private void givenActiveRelation() {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(ELDER_ID, GUARDIAN_ID));
    }

    private static GuardianRelation relation(String elderId, String guardianId) {
        GuardianRelation relation = new GuardianRelation();
        relation.setRelationId("r-1001");
        relation.setElderId(elderId);
        relation.setGuardianId(guardianId);
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

    private static Device mainDevice(String deviceId, String elderId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceType(DeviceType.MAIN);
        device.setElderId(elderId);
        device.setProductKey("medbox-box");
        device.setTimezone("Asia/Shanghai");
        device.setOnlineState(OnlineState.ONLINE);
        device.setCreatedAt(OffsetDateTime.parse("2026-10-12T00:00:00Z"));
        return device;
    }

    private static Device sensor(String deviceId, String parentDeviceId, String elderId, OnlineState state) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceType(DeviceType.TEMP_HUMI);
        device.setParentDeviceId(parentDeviceId);
        device.setElderId(elderId);
        device.setOnlineState(state);
        device.setLastHeartbeat(OffsetDateTime.parse("2026-10-13T01:00:00Z"));
        return device;
    }
}

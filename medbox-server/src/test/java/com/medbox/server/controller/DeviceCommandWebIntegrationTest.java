package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.DeviceCommand;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.DeviceCommandMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.mqtt.DownlinkPublisher;
import com.medbox.server.mqtt.PendingAckRegistry;
import com.medbox.server.service.DeviceEventService;
import com.medbox.server.support.TableInfoWarmup;
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
 * {@code POST /devices/{deviceId}/commands} 的**集成测试**（{@code feat/device-command}，
 * 见文档 02 第 4 章）。
 *
 * <p>完整链路：MockMvc → {@code DeviceController} → 真实 {@code DeviceCommandService}
 * → 真实 {@code AccessService}（40301/40302 口径）→ 打桩的 Mapper / 下行发布器。
 *
 * <p><b>测试期 MQTT 整体不装（{@code MEDBOX_MQTT_ENABLED=false}）</b>，故用 {@code @MockitoBean}
 * 补 {@link DownlinkPublisher} / {@link PendingAckRegistry} 两个假 Bean 让服务可用；
 * 真实 QoS1 收发与重发归 {@code PendingAckRegistry} 既有测试与真机联调。
 * 嵌入式 H2 仅为事务开连接，Mapper 全部打桩，不跑真实 SQL。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceCommandWebIntegrationTest {

    /** 嵌入式 H2 替换 PostgreSQL DataSource：CI 无 Postgres，事务可开、不跑真实 SQL。 */
    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test-command")
                    .build();
        }
    }

    private static final String ELDER_ID = "u-1001";
    private static final String OTHER_ELDER_ID = "u-1002";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String DEVICE_ID = "BOXA1001";
    private static final String SENSOR_ID = "BOXA1001-S-TH01";

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

    @MockitoBean
    private DeviceCommandMapper commandMapper;

    @MockitoBean
    private DeviceEventService deviceEventService;

    @MockitoBean
    private DownlinkPublisher downlinkPublisher;

    @MockitoBean
    private PendingAckRegistry pendingAckRegistry;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class, User.class, GuardianRelation.class, DeviceCommand.class);
        // AccessService 监护人路径先 isElder(elderId)：userMapper 不 stub 会一律 40301
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any()))
                .thenReturn(elder(ELDER_ID));
        when(commandMapper.nextCommandId()).thenReturn("c-1001");
        when(commandMapper.insert(any(DeviceCommand.class))).thenReturn(1);
        when(commandMapper.update(any(), ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DeviceCommand>>any()))
                .thenReturn(1);
        when(downlinkPublisher.publish(eq(DEVICE_ID), eq(DownlinkPublisher.SUB_TOPIC_COMMAND), any(), eq(true)))
                .thenReturn("d-cmd-1");
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("监护人（FAMILY）下发：受理返回 commandId + PENDING")
    void 监护人下发成功() throws Exception {
        givenActiveRelation();
        givenDevice(mainDevice(DEVICE_ID, ELDER_ID, OnlineState.ONLINE));

        mockMvc.perform(post("/api/v1/devices/{deviceId}/commands", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"BUZZ\",\"params\":{\"durationSec\":5}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.commandId").value("c-1001"))
                .andExpect(jsonPath("$.data.state").value("PENDING"));
    }

    @Test
    @DisplayName("未登录 → 40101")
    void 未登录_40101() throws Exception {
        mockMvc.perform(post("/api/v1/devices/{deviceId}/commands", DEVICE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"BUZZ\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("老人本人下发 → 40301（WRITE_DEVICE 仅 FAMILY 监护人）")
    void 老人本人_40301() throws Exception {
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any()))
                .thenReturn(elder(ELDER_ID));
        givenDevice(mainDevice(DEVICE_ID, ELDER_ID, OnlineState.ONLINE));

        mockMvc.perform(post("/api/v1/devices/{deviceId}/commands", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"BUZZ\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("设备不存在 → 40301（防设备编号枚举）")
    void 设备不存在_40301() throws Exception {
        givenActiveRelation();
        givenDevice(null);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/commands", "NOT-EXIST")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"BUZZ\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("传子设备编号 → 40301（deviceId 只接受药箱主控）")
    void 子设备编号_40301() throws Exception {
        givenActiveRelation();
        Device sensor = new Device();
        sensor.setDeviceId(SENSOR_ID);
        sensor.setDeviceType(DeviceType.TEMP_HUMI);
        sensor.setElderId(ELDER_ID);
        sensor.setOnlineState(OnlineState.ONLINE);
        givenDevice(sensor);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/commands", SENSOR_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"BUZZ\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("越权（无监护关系）→ 40301")
    void 越权_40301() throws Exception {
        // 刻意不建到 OTHER_ELDER 的监护关系
        givenDevice(mainDevice(DEVICE_ID, OTHER_ELDER_ID, OnlineState.ONLINE));

        mockMvc.perform(post("/api/v1/devices/{deviceId}/commands", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"BUZZ\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("设备离线 → 40901（小程序按此码提示'设备离线，无法下发'）")
    void 设备离线_40901() throws Exception {
        givenActiveRelation();
        givenDevice(mainDevice(DEVICE_ID, ELDER_ID, OnlineState.OFFLINE));

        mockMvc.perform(post("/api/v1/devices/{deviceId}/commands", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"BUZZ\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("未知 cmd → 40001")
    void 未知命令_40001() throws Exception {
        givenActiveRelation();
        givenDevice(mainDevice(DEVICE_ID, ELDER_ID, OnlineState.ONLINE));

        mockMvc.perform(post("/api/v1/devices/{deviceId}/commands", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmd\":\"EXPLODE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private void givenActiveRelation() {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(ELDER_ID, GUARDIAN_ID));
    }

    private void givenDevice(Device device) {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(device);
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

    private static Device mainDevice(String deviceId, String elderId, OnlineState state) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceType(DeviceType.MAIN);
        device.setElderId(elderId);
        device.setProductKey("medbox-box");
        device.setOnlineState(state);
        return device;
    }
}

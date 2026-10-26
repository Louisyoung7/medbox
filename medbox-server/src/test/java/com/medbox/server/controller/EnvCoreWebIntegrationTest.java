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
import com.medbox.server.domain.EnvSample;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.dto.env.EnvHistoryPoint;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.EnvSampleMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.math.BigDecimal;
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
 * 环境监测接口（{@code /env/realtime}、{@code /env/history} 与设备详情 {@code latestTelemetry}）的**集成测试**
 * （{@code feat/env-core}，见文档 02 第 9 章 / 第 4 章）。
 *
 * <p>完整链路：MockMvc → {@code EnvController} / {@code DeviceController} → 真实 {@code EnvSampleService}
 * → 真实 {@code AccessService} / {@code DeviceOwnerResolverImpl} → 打桩的 Mapper。
 * 权限判定（40301 防枚举口径）与"实时读数合并 / 历史分桶校验"都在这里验。
 *
 * <p><b>不连真实数据库</b>：CI 里没有 PostgreSQL，Mapper 全部打桩；{@code @Primary} 嵌入式 H2
 * 仅为可能触达的事务开连接，不跑真实 SQL（SQL 正确性留真机联调）。
 *
 * <p>上行入库（{@code up/telemetry} → {@code TelemetryUpMessageHandler}）归 {@code TelemetryUpMessageHandlerTest} /
 * {@code EnvSampleServiceTest}（MQTT 装配在测试期整体不建，handler 逻辑由纯单测覆盖）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnvCoreWebIntegrationTest {

    /** 嵌入式 H2 替换 PostgreSQL DataSource：CI 无 Postgres，事务可开、不跑真实 SQL。 */
    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test-env")
                    .build();
        }
    }

    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String DEVICE_ID = "BOXA1001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private DeviceMapper deviceMapper;

    @MockitoBean
    private EnvSampleMapper envSampleMapper;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class, User.class, GuardianRelation.class, EnvSample.class);
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any()))
                .thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("监护人读实时读数：合并温湿度与光照，sampleTime 取较新者")
    void realtime_200() throws Exception {
        givenActiveRelation();
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));

        EnvSample th = sample("24.6", "52.1", null, "2026-10-26T01:00:00Z");
        EnvSample lx = sample(null, null, "120", "2026-10-26T01:05:00Z");
        when(envSampleMapper.selectLatestTempHumi(ArgumentMatchers.eq(DEVICE_ID), ArgumentMatchers.any()))
                .thenReturn(th);
        when(envSampleMapper.selectLatestLux(ArgumentMatchers.eq(DEVICE_ID), ArgumentMatchers.any()))
                .thenReturn(lx);

        mockMvc.perform(get("/api/v1/env/realtime").param("deviceId", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temperature").value(24.6))
                .andExpect(jsonPath("$.data.humidity").value(52.1))
                .andExpect(jsonPath("$.data.lux").value(120))
                .andExpect(jsonPath("$.data.sampleTime").value("2026-10-26T01:05:00Z"));
    }

    @Test
    @DisplayName("监护人读历史曲线：返回分桶点")
    void history_200() throws Exception {
        givenActiveRelation();
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));

        EnvHistoryPoint point = new EnvHistoryPoint(
                OffsetDateTime.parse("2026-10-26T01:00:00Z"),
                new BigDecimal("24.6"), new BigDecimal("52.1"), new BigDecimal("120"));
        when(envSampleMapper.selectHistory(ArgumentMatchers.eq(DEVICE_ID), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(List.of(point));

        mockMvc.perform(get("/api/v1/env/history").param("deviceId", DEVICE_ID)
                        .param("from", "2026-10-26T00:00:00Z")
                        .param("to", "2026-10-26T02:00:00Z")
                        .param("interval", "10")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points.length()").value(1))
                .andExpect(jsonPath("$.data.points[0].temperature").value(24.6))
                .andExpect(jsonPath("$.data.points[0].lux").value(120));
    }

    @Test
    @DisplayName("未登录 → 40101")
    void 未登录返回_40101() throws Exception {
        mockMvc.perform(get("/api/v1/env/realtime").param("deviceId", DEVICE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("设备不存在 → 40301（与越权同码，防设备编号枚举）")
    void 设备不存在返回_40301() throws Exception {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(null);

        mockMvc.perform(get("/api/v1/env/realtime").param("deviceId", "NOT-EXIST")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("interval 越界 → 40001")
    void interval越界返回_40001() throws Exception {
        givenActiveRelation();
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));

        mockMvc.perform(get("/api/v1/env/history").param("deviceId", DEVICE_ID)
                        .param("from", "2026-10-26T00:00:00Z")
                        .param("to", "2026-10-26T01:00:00Z")
                        .param("interval", "2000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("设备详情携带 latestTelemetry 透传")
    void 设备详情最新遥测透传() throws Exception {
        givenActiveRelation();
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));
        when(deviceMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(List.of());

        EnvSample th = sample("24.6", "52.1", null, "2026-10-26T01:00:00Z");
        when(envSampleMapper.selectLatestTempHumi(ArgumentMatchers.eq(DEVICE_ID), ArgumentMatchers.any()))
                .thenReturn(th);
        when(envSampleMapper.selectLatestLux(ArgumentMatchers.eq(DEVICE_ID), ArgumentMatchers.any()))
                .thenReturn(null);

        mockMvc.perform(get("/api/v1/devices/{deviceId}", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestTelemetry.temperature").value(24.6))
                .andExpect(jsonPath("$.data.latestTelemetry.sampleTime").value("2026-10-26T01:00:00Z"));
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
        relation.setRole(RelationRole.FAMILY);
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

    private static EnvSample sample(String temp, String humi, String lux, String time) {
        EnvSample s = new EnvSample();
        s.setDeviceId(DEVICE_ID);
        s.setGatewayDeviceId(DEVICE_ID);
        s.setElderId(ELDER_ID);
        s.setTemperature(temp == null ? null : new BigDecimal(temp));
        s.setHumidity(humi == null ? null : new BigDecimal(humi));
        s.setLux(lux == null ? null : new BigDecimal(lux));
        s.setSampleTime(OffsetDateTime.parse(time));
        return s;
    }
}

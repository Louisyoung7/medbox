package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.DeviceEvent;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.DeviceEventType;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.DeviceEventMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.EnvSampleMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.time.OffsetDateTime;
import java.util.List;
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
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 设备主干链路的**集成测试**（{@code feat/device-core}，见文档 02 第 4 章 / 06 的 2.16）。
 *
 * <p>与 {@code DeviceControllerTest} 的分工：那边打桩 Service、只验接口契约；
 * 这里是**完整链路** —— MockMvc → {@code DeviceController} → 真实 {@code DeviceService}
 * → 真实 {@code AccessService} → 真实 {@code DeviceOwnerResolverImpl} → 打桩的 Mapper。
 * 权限矩阵、注册冲突、行级过滤、事件流水这些真正的业务规则都在这里验。
 *
 * <p><b>不连真实数据库</b>：CI 里没有 PostgreSQL（{@code build.gradle.kts} 已关掉 Flyway），
 * Mapper 全部打桩。但 {@code register} 是 {@code @Transactional}，进入方法时 Spring 要开一个 JDBC 连接，
 * 故用 {@link H2DataSourceConfig} 把 DataSource 替换成<b>嵌入式 H2</b>（内存库，不建表、Mapper 仍是 mock），
 * 事务可正常开启、且不依赖外部 Postgres。这让测试覆盖"判定逻辑"而不是"SQL 是否写对" —— 后者靠真机联调。
 *
 * <p>token 由真实 {@link JwtProvider} 签发，第 ① 步不绕过。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceCoreWebIntegrationTest {

    /**
     * 用嵌入式 H2 替换真实的 PostgreSQL DataSource：CI 里没有 Postgres，而 {@code register} 是
     * {@code @Transactional}，Spring 进入方法时要开一个 JDBC 连接。H2 内存库可正常开事务，
     * 而 Mapper 仍是 mock，不跑任何真实 SQL。这样测试既不依赖外部数据库，又能覆盖完整业务链路。
     */
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
    private static final String OTHER_ELDER_ID = "u-1002";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String STRANGER_ID = "u-9999";
    private static final String DEVICE_ID = "BOXA1001";
    private static final String SENSOR_TH = "BOXA1001-S-TH01";
    private static final String SENSOR_CAM = "BOXA1001-S-CAM01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private DeviceMapper deviceMapper;

    @MockitoBean
    private DeviceEventMapper deviceEventMapper;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    /** env-core 落地后 detail() 会顺带查最新遥测；本测试用 H2 且未跑 Flyway，故打桩返回 null。 */
    @MockitoBean
    private EnvSampleMapper envSampleMapper;

    @BeforeEach
    void setUp() {
        // Mapper 被打桩后 MyBatis 不会为实体建表信息，而 in(...) 要立即解析列名 —— 先预热，否则 500
        TableInfoWarmup.init(Device.class, DeviceEvent.class, User.class, GuardianRelation.class);

        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any()))
                .thenReturn(elder(ELDER_ID));
        when(deviceMapper.selectCount(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(0L);
        when(envSampleMapper.selectLatestTempHumi(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(null);
        when(envSampleMapper.selectLatestLux(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    // ————————————————— 注册 —————————————————

    @Test
    @DisplayName("家属代老人注册：主控 + 2 个子设备一起落库，各写 1 条 REGISTERED 事件")
    void 注册主控与子设备() throws Exception {
        givenActiveRelation();

        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001",
                                 "sensors":[{"deviceId":"BOXA1001-S-TH01","deviceType":"TEMP_HUMI"},
                                            {"deviceId":"BOXA1001-S-CAM01","deviceType":"CAMERA"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(DEVICE_ID))
                .andExpect(jsonPath("$.data.productKey").value("medbox-box"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.sensors.length()").value(2));

        // 主控 1 条 + 子设备 2 条
        verify(deviceMapper, times(3)).insert(any(Device.class));
        verify(deviceEventMapper, times(3)).insert(any(DeviceEvent.class));

        ArgumentCaptor<Device> inserted = ArgumentCaptor.forClass(Device.class);
        verify(deviceMapper, times(3)).insert(inserted.capture());
        List<Device> all = inserted.getAllValues();
        org.junit.jupiter.api.Assertions.assertEquals(DeviceType.MAIN, all.get(0).getDeviceType());
        org.junit.jupiter.api.Assertions.assertNull(all.get(0).getParentDeviceId());
        // 子设备要指回主控，且 elder_id 与父设备一致（行级权限与按老人查询都靠它）
        org.junit.jupiter.api.Assertions.assertEquals(DEVICE_ID, all.get(1).getParentDeviceId());
        org.junit.jupiter.api.Assertions.assertEquals(ELDER_ID, all.get(1).getElderId());
        org.junit.jupiter.api.Assertions.assertEquals(ELDER_ID, all.get(2).getElderId());
    }

    @Test
    @DisplayName("重复登记 → 40901，且不写任何数据")
    void 重复登记返回_40901() throws Exception {
        givenActiveRelation();
        when(deviceMapper.selectCount(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(1L);

        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));

        verify(deviceMapper, never()).insert(any(Device.class));
        verify(deviceEventMapper, never()).insert(any(DeviceEvent.class));
    }

    @Test
    @DisplayName("子设备类型填 MAIN → 40001")
    void 子设备类型不可为主控() throws Exception {
        givenActiveRelation();

        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001",
                                 "sensors":[{"deviceId":"BOXA1002","deviceType":"MAIN"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("子设备编号与主控重复 → 40001")
    void 子设备编号重复返回_40001() throws Exception {
        givenActiveRelation();

        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001",
                                 "sensors":[{"deviceId":"BOXA1001","deviceType":"TEMP_HUMI"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("非法时区 → 40001（避免把错误值写进库影响服药计划换算）")
    void 非法时区返回_40001() throws Exception {
        givenActiveRelation();

        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001","timezone":"Mars/Olympus"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("老人本人注册 → 40301（WRITE_DEVICE 只对 FAMILY 监护人开放）")
    void 老人自注册返回_40301() throws Exception {
        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(deviceMapper, never()).insert(any(Device.class));
    }

    @Test
    @DisplayName("监护人注册设备 → 200（GUARDIAN 统一角色，WRITE_DEVICE 放行）")
    void 监护人可注册() throws Exception {
        givenActiveRelation();

        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001"}"""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PENDING 监护关系注册 → 40302")
    void 监护关系未生效返回_40302() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.PENDING));

        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    // ————————————————— 四个查询 —————————————————

    @Test
    @DisplayName("设备列表：只返回我监护老人的主控，并带上子设备数量")
    void 设备列表带子设备数量() throws Exception {
        when(guardianRelationMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(List.of(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE)));
        // 第一次 selectList 取主控，第二次取子设备（Service 里刻意只查两次，避免 N+1）
        when(deviceMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(List.of(mainDevice(DEVICE_ID, ELDER_ID), mainDevice("BOXA1002", ELDER_ID)))
                .thenReturn(List.of(sensor(SENSOR_TH, DEVICE_ID, ELDER_ID),
                        sensor(SENSOR_CAM, DEVICE_ID, ELDER_ID),
                        sensor("BOXA1002-S-TH01", "BOXA1002", ELDER_ID)));

        mockMvc.perform(get("/api/v1/devices")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].deviceId").value(DEVICE_ID))
                .andExpect(jsonPath("$.data[0].sensorCount").value(2))
                .andExpect(jsonPath("$.data[1].deviceId").value("BOXA1002"))
                .andExpect(jsonPath("$.data[1].sensorCount").value(1));
    }

    @Test
    @DisplayName("设备列表：无任何监护关系 → 空数组，且不查设备表")
    void 无可见老人返回空列表() throws Exception {
        when(guardianRelationMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/devices")
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(deviceMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("设备详情：档案 + 子设备清单及各自在线状态")
    void 设备详情含子设备() throws Exception {
        Device main = mainDevice(DEVICE_ID, ELDER_ID);
        main.setOnlineState(OnlineState.ONLINE);
        main.setLastHeartbeat(OffsetDateTime.parse("2026-10-13T01:00:00Z"));
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(main);
        when(deviceMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(List.of(sensor(SENSOR_TH, DEVICE_ID, ELDER_ID)));

        mockMvc.perform(get("/api/v1/devices/{deviceId}", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(DEVICE_ID))
                .andExpect(jsonPath("$.data.onlineState").value("ONLINE"))
                .andExpect(jsonPath("$.data.sensors.length()").value(1))
                .andExpect(jsonPath("$.data.sensors[0].deviceId").value(SENSOR_TH));
    }

    @Test
    @DisplayName("实时状态：老人本人可读自己的设备")
    void 老人可读自己设备的状态() throws Exception {
        Device main = mainDevice(DEVICE_ID, ELDER_ID);
        main.setOnlineState(OnlineState.OFFLINE);
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(main);

        mockMvc.perform(get("/api/v1/devices/{deviceId}/status", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onlineState").value("OFFLINE"));
    }

    @Test
    @DisplayName("越权：访问他人设备 → 40301（不是 40401，防设备编号枚举）")
    void 越权访问他人设备返回_40301() throws Exception {
        // 刻意不建"到 OTHER_ELDER 的监护关系"：监护人对该老人无关系 → 40301
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, OTHER_ELDER_ID));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/status", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("设备不存在 → 40301（与越权同码）")
    void 设备不存在返回_40301() throws Exception {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(null);

        mockMvc.perform(get("/api/v1/devices/{deviceId}", "NOT-EXIST")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("传子设备编号查详情 → 40301（deviceId 只接受药箱主控）")
    void 传子设备编号返回_40301() throws Exception {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(sensor(SENSOR_TH, DEVICE_ID, ELDER_ID));

        mockMvc.perform(get("/api/v1/devices/{deviceId}", SENSOR_TH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("事件流水：分页返回，按时间倒序")
    void 事件流水分页() throws Exception {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));
        when(deviceEventMapper.selectPage(ArgumentMatchers.<Page<DeviceEvent>>any(),
                ArgumentMatchers.<LambdaQueryWrapper<DeviceEvent>>any()))
                .thenAnswer(invocation -> {
                    Page<DeviceEvent> page = invocation.getArgument(0);
                    page.setRecords(List.of(
                            event(2L, DeviceEventType.ONLINE),
                            event(1L, DeviceEventType.REGISTERED)));
                    page.setTotal(2L);
                    return page;
                });

        mockMvc.perform(get("/api/v1/devices/{deviceId}/events", DEVICE_ID)
                        .param("page", "1")
                        .param("size", "20")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.list[0].eventType").value("ONLINE"))
                .andExpect(jsonPath("$.data.list[1].eventType").value("REGISTERED"));
    }

    @Test
    @DisplayName("事件流水：page=0 → 40001")
    void 事件流水页码非法返回_40001() throws Exception {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/events", DEVICE_ID)
                        .param("page", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private void givenActiveRelation() {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE));
    }

    private static GuardianRelation relation(String elderId, String guardianId, RelationStatus status) {
        GuardianRelation relation = new GuardianRelation();
        relation.setRelationId("r-1001");
        relation.setElderId(elderId);
        relation.setGuardianId(guardianId);
        relation.setStatus(status);
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
        device.setOnlineState(OnlineState.OFFLINE);
        device.setCreatedAt(OffsetDateTime.parse("2026-10-12T00:00:00Z"));
        return device;
    }

    private static Device sensor(String deviceId, String parentDeviceId, String elderId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceType(DeviceType.TEMP_HUMI);
        device.setParentDeviceId(parentDeviceId);
        device.setElderId(elderId);
        device.setOnlineState(OnlineState.OFFLINE);
        return device;
    }

    private static DeviceEvent event(long id, DeviceEventType type) {
        DeviceEvent event = new DeviceEvent();
        event.setId(id);
        event.setDeviceId(DEVICE_ID);
        event.setEventType(type);
        return event;
    }
}

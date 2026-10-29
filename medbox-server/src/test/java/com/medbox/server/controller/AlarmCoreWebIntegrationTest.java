package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.AlarmSetting;
import com.medbox.server.domain.EnvThreshold;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.AlarmSettingMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.support.TableInfoWarmup;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 告警查询 / 处理 / 阈值配置的集成测试（{@code feat/alarm-core}，见文档 02 第 8 章 / 07）。
 *
 * <p>真实链路：MockMvc → Controller → 真实 Service → 真实 {@code AccessService} → 打桩的 Mapper
 * （不跑真实 SQL，用内嵌 H2 顶替 DataSource）。覆盖：列表/未读/标记已读/处理连通、权限矩阵
 * （FAMILY+NURSE 可处理、ELDER/DOCTOR/陌生人 40301、PENDING 40302）、告警不存在 40401、
 * 阈值配置 PUT 仅 FAMILY 可写 + envThreshold JSONB 往返。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AlarmCoreWebIntegrationTest {

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
    private static final String NURSE_ID = "u-2002";
    private static final String DOCTOR_ID = "u-2003";
    private static final String STRANGER_ID = "u-9999";
    private static final String ALARM_ID = "a-1001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private AlarmMapper alarmMapper;
    @MockitoBean
    private AlarmSettingMapper settingMapper;
    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Alarm.class, AlarmSetting.class, User.class, GuardianRelation.class);
        // AccessService.isElder 用 userMapper 校验目标是否老人
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    // ————————————————— 列表 / 未读 —————————————————

    @Test
    @DisplayName("列表：FAMILY 监护人 → 200，分页结构正确")
    void 列表返回_200() throws Exception {
        givenActiveRelation(GUARDIAN_ID, ELDER_ID, RelationRole.FAMILY);
        when(alarmMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<Alarm> page = inv.getArgument(0);
                    page.setRecords(List.of(alarm()));
                    page.setTotal(1);
                    return page;
                });

        mockMvc.perform(get("/api/v1/alarms")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].alarmId").value(ALARM_ID))
                .andExpect(jsonPath("$.data.list[0].type").value("MISS"));
    }

    @Test
    @DisplayName("未读角标：FAMILY → 200，返回未读数")
    void 未读角标_200() throws Exception {
        givenActiveRelation(GUARDIAN_ID, ELDER_ID, RelationRole.FAMILY);
        when(alarmMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        mockMvc.perform(get("/api/v1/alarms/unread-count")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(2));
    }

    // ————————————————— 标记已读 / 处理 —————————————————

    @Test
    @DisplayName("标记已读：FAMILY → 200")
    void 标记已读_200() throws Exception {
        givenActiveRelation(GUARDIAN_ID, ELDER_ID, RelationRole.FAMILY);
        when(alarmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(alarm());

        mockMvc.perform(patch("/api/v1/alarms/{alarmId}/read", ALARM_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("处理：FAMILY → 200")
    void 处理_FAMILY_200() throws Exception {
        givenActiveRelation(GUARDIAN_ID, ELDER_ID, RelationRole.FAMILY);
        when(alarmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(alarm());

        mockMvc.perform(patch("/api/v1/alarms/{alarmId}/handle", ALARM_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("处理：NURSE（GUARDIAN 用户 + NURSE 监护关系）→ 200")
    void 处理_NURSE_200() throws Exception {
        givenActiveRelation(NURSE_ID, ELDER_ID, RelationRole.NURSE);
        when(alarmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(alarm());

        mockMvc.perform(patch("/api/v1/alarms/{alarmId}/handle", ALARM_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(NURSE_ID, "GUARDIAN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("处理：ELDER 本人 → 40301（无 HANDLE_ALARM 权限）")
    void 处理_ELDER_40301() throws Exception {
        when(alarmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(alarm());

        mockMvc.perform(patch("/api/v1/alarms/{alarmId}/handle", ALARM_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("处理：DOCTOR（GUARDIAN 用户 + DOCTOR 监护关系）→ 40301（无 HANDLE_ALARM 权限）")
    void 处理_DOCTOR_40301() throws Exception {
        givenActiveRelation(DOCTOR_ID, ELDER_ID, RelationRole.DOCTOR);
        when(alarmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(alarm());

        mockMvc.perform(patch("/api/v1/alarms/{alarmId}/handle", ALARM_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(DOCTOR_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("处理：告警不存在 → 40401")
    void 处理_告警不存在_40401() throws Exception {
        givenActiveRelation(GUARDIAN_ID, ELDER_ID, RelationRole.FAMILY);
        when(alarmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mockMvc.perform(patch("/api/v1/alarms/{alarmId}/handle", ALARM_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("处理：监护关系 PENDING → 40302")
    void 处理_PENDING_40302() throws Exception {
        givenPendingRelation(GUARDIAN_ID, ELDER_ID, RelationRole.FAMILY);
        when(alarmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(alarm());

        mockMvc.perform(patch("/api/v1/alarms/{alarmId}/handle", ALARM_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    @Test
    @DisplayName("列表：陌生人（无监护关系）→ 40301")
    void 列表_陌生人_40301() throws Exception {
        when(guardianRelationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of());
        when(guardianRelationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mockMvc.perform(get("/api/v1/alarms")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ————————————————— 阈值配置 —————————————————

    @Test
    @DisplayName("PUT 阈值：FAMILY → 200，envThreshold JSONB 往返")
    void 配置阈值_FAMILY_200() throws Exception {
        givenActiveRelation(GUARDIAN_ID, ELDER_ID, RelationRole.FAMILY);
        when(settingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mockMvc.perform(put("/api/v1/alarm-settings")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"missTolerateMin":20,
                                 "envThreshold":{"tempMin":10,"tempMax":30,"humiMin":30,
                                                 "humiMax":70,"luxMax":500}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missTolerateMin").value(20))
                .andExpect(jsonPath("$.data.envThreshold.tempMin").value(10))
                .andExpect(jsonPath("$.data.envThreshold.luxMax").value(500));
    }

    @Test
    @DisplayName("PUT 阈值：ELDER 本人 → 40301（仅 FAMILY 可写）")
    void 配置阈值_ELDER_40301() throws Exception {
        mockMvc.perform(put("/api/v1/alarm-settings")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("PUT 阈值：NURSE（GUARDIAN 用户 + NURSE 监护关系）→ 40301（仅 FAMILY 可写）")
    void 配置阈值_NURSE_40301() throws Exception {
        givenActiveRelation(NURSE_ID, ELDER_ID, RelationRole.NURSE);
        mockMvc.perform(put("/api/v1/alarm-settings")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(NURSE_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("GET 阈值：FAMILY → 200")
    void 查询阈值_FAMILY_200() throws Exception {
        givenActiveRelation(GUARDIAN_ID, ELDER_ID, RelationRole.FAMILY);
        AlarmSetting setting = new AlarmSetting();
        setting.setElderId(ELDER_ID);
        setting.setMissTolerateMin(15);
        setting.setEnvThreshold(new EnvThreshold(10, 30, 30, 70, 500));
        when(settingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);

        mockMvc.perform(get("/api/v1/alarm-settings")
                        .param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missTolerateMin").value(15))
                .andExpect(jsonPath("$.data.envThreshold.tempMax").value(30));
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private void givenActiveRelation(String guardianId, String elderId, RelationRole role) {
        List<GuardianRelation> relations = List.of(relation(elderId, guardianId, role, RelationStatus.ACTIVE));
        when(guardianRelationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(relations);
        when(guardianRelationMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(relations.get(0));
    }

    private void givenPendingRelation(String guardianId, String elderId, RelationRole role) {
        List<GuardianRelation> relations = List.of(relation(elderId, guardianId, role, RelationStatus.PENDING));
        when(guardianRelationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(relations);
        when(guardianRelationMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(relations.get(0));
    }

    private static Alarm alarm() {
        Alarm a = new Alarm();
        a.setId(1L);
        a.setAlarmId(ALARM_ID);
        a.setElderId(ELDER_ID);
        a.setType("MISS");
        a.setLevel("WARN");
        a.setReadState(false);
        a.setHandleState("OPEN");
        return a;
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

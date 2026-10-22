package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.MedicineStock;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.MedicineStockMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.service.AccessService;
import com.medbox.server.service.PlanService;
import com.medbox.server.support.TableInfoWarmup;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
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
 * 服药计划集成测试（{@code feat/plan-core}，见文档 02 第 6 章 / 06 的 2.6、2.6.1）。
 *
 * <p>真实链路：MockMvc → Controller → 真实 {@code PlanService} → 真实 {@code AccessService}
 *  → 打桩的 Mapper（不跑真实 SQL，H2 仅充当 DataSource 让事务可开）。
 * 覆盖：权限(40301)、times 校验(40001)、库存/过期(40901)、创建/修改(PUT)整体覆盖、
 * 列表/详情、启停、级联删除、以及 next-doses 的时区换算与 DAILY 展开。
 *
 * <p><b>时区换算要可确定性断言</b>：{@code PlanService} 的 {@code Clock} 不能由测试注入
 * （它的 @Autowired 构造固定用 {@code Clock.systemUTC()}），故这里用 {@code @Primary} 注册一个
 * 固定时刻的 {@code PlanService} 单例接管 Controller 注入，确保 next-doses 的 now 可预期。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanCoreWebIntegrationTest {

    /** 固定时刻：2026-10-21T00:00:00Z（UTC 午夜）。上海本地 = 当日 08:00。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);

    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String STRANGER_ID = "u-9999";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private MedPlanMapper medPlanMapper;

    @MockitoBean
    private MedPlanItemMapper medPlanItemMapper;

    @MockitoBean
    private MedicineMapper medicineMapper;

    @MockitoBean
    private MedicineStockMapper medicineStockMapper;

    @MockitoBean
    private DeviceMapper deviceMapper;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(MedPlan.class, MedPlanItem.class, Medicine.class,
                MedicineStock.class, Device.class, User.class, GuardianRelation.class);
        when(userMapper.selectOne(any())).thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    @TestConfiguration
    static class H2AndFixedClockConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test")
                    .build();
        }

        /** 用固定 Clock 的 PlanService 覆盖默认（@Autowired 固定 systemUTC 的那个）。 */
        @Bean
        @Primary
        public PlanService planService(MedPlanMapper medPlanMapper, MedPlanItemMapper medPlanItemMapper,
                                      MedicineMapper medicineMapper, MedicineStockMapper medicineStockMapper,
                                      DeviceMapper deviceMapper, AccessService accessService,
                                      org.springframework.context.ApplicationEventPublisher eventPublisher) {
            return new PlanService(medPlanMapper, medPlanItemMapper, medicineMapper,
                    medicineStockMapper, deviceMapper, accessService, FIXED_CLOCK, eventPublisher);
        }
    }

    // ———————————————— 创建 ————————————————

    @Test
    @DisplayName("创建：FAMILY 监护人 + 有未过期库存 → 200，业务号/状态/syncState 正确")
    void 创建成功() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "Asia/Shanghai");
        givenMedicine("m-1001");
        givenStock(ELDER_ID, "m-1001", 5, LocalDate.parse("2026-12-31"));

        when(medPlanMapper.nextPlanId()).thenReturn("p-1001");
        when(medPlanMapper.insert(any(MedPlan.class))).thenReturn(1);
        when(medPlanItemMapper.nextItemId()).thenReturn("pi-1001");
        when(medPlanItemMapper.insert(any(MedPlanItem.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","name":"早餐后",
                                 "times":["08:00"],"repeatRule":"DAILY",
                                 "items":[{"medicineId":"m-1001","dose":"1","unit":"片"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planId").value("p-1001"))
                .andExpect(jsonPath("$.data.status").value("ENABLED"))
                .andExpect(jsonPath("$.data.syncState").value("PENDING"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].itemId").value("pi-1001"));
    }

    @Test
    @DisplayName("创建：引用的药品不存在 → 40401")
    void 创建药品不存在() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "Asia/Shanghai");
        when(medicineMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","times":["08:00"],
                                 "items":[{"medicineId":"m-9999"}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("创建：药品无合格库存 / 已过期 → 40901")
    void 创建库存或过期() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "Asia/Shanghai");
        givenMedicine("m-1001");
        // 过期库存（expiry 在今日之前）：2026-10-21 上海本地今日 = 2026-10-21，expiry 2026-10-01 已过期
        givenStock(ELDER_ID, "m-1001", 5, LocalDate.parse("2026-10-01"));

        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","times":["08:00"],
                                 "items":[{"medicineId":"m-1001"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));

        verify(medPlanMapper, never()).insert(any(MedPlan.class));
    }

    @Test
    @DisplayName("创建：times 格式非法 → 40001")
    void 创建Times非法() throws Exception {
        givenGuardianGuards(ELDER_ID);

        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","times":["25:00"],
                                 "items":[{"medicineId":"m-1001"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("创建：同一计划内药品重复 → 40001")
    void 创建药品重复() throws Exception {
        givenGuardianGuards(ELDER_ID);

        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","times":["08:00"],
                                 "items":[{"medicineId":"m-1001"},{"medicineId":"m-1001"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("创建：老人本人（ELDER）无 WRITE_PLAN → 40301")
    void 老人不可创建() throws Exception {
        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","times":["08:00"],
                                 "items":[{"medicineId":"m-1001"}]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(medPlanMapper, never()).insert(any(MedPlan.class));
    }

    // ———————————————— 修改（PUT 整体覆盖） ————————————————

    @Test
    @DisplayName("修改：FAMILY 监护人 + 有未过期库存 → 200，字段整体覆盖、旧明细先删后插")
    void 修改成功() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "Asia/Shanghai");
        givenMedicine("m-1001");
        givenStock(ELDER_ID, "m-1001", 5, LocalDate.parse("2026-12-31"));
        when(medPlanMapper.selectOne(any()))
                .thenReturn(plan("p-1001", ELDER_ID));
        when(medPlanMapper.updateById(any(MedPlan.class))).thenReturn(1);
        when(medPlanItemMapper.delete(any())).thenReturn(1);
        when(medPlanItemMapper.nextItemId()).thenReturn("pi-2001");
        when(medPlanItemMapper.insert(any(MedPlanItem.class))).thenReturn(1);

        mockMvc.perform(put("/api/v1/plans/{planId}", "p-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","name":"晚餐后",
                                 "times":["20:00"],"repeatRule":"DAILY",
                                 "items":[{"medicineId":"m-1001","dose":"2","unit":"片"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planId").value("p-1001"))
                .andExpect(jsonPath("$.data.name").value("晚餐后"))
                .andExpect(jsonPath("$.data.times[0]").value("20:00"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].itemId").value("pi-2001"))
                .andExpect(jsonPath("$.data.items[0].dose").value("2"));

        verify(medPlanMapper).updateById(any(MedPlan.class));
        verify(medPlanItemMapper).delete(any());
    }

    @Test
    @DisplayName("修改：times 格式非法 → 40001（findForWrite 先定位、再校验）")
    void 修改Times非法() throws Exception {
        when(medPlanMapper.selectOne(any()))
                .thenReturn(plan("p-1001", ELDER_ID));
        givenGuardianGuards(ELDER_ID);

        mockMvc.perform(put("/api/v1/plans/{planId}", "p-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"times":["25:00"],"items":[{"medicineId":"m-1001"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        verify(medPlanMapper, never()).updateById(any(MedPlan.class));
    }

    @Test
    @DisplayName("修改：引用的药品不存在 → 40401")
    void 修改药品不存在() throws Exception {
        when(medPlanMapper.selectOne(any()))
                .thenReturn(plan("p-1001", ELDER_ID));
        givenGuardianGuards(ELDER_ID);
        when(medicineMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(put("/api/v1/plans/{planId}", "p-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"times":["08:00"],"items":[{"medicineId":"m-9999"}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("修改：药品无合格库存 / 已过期 → 40901")
    void 修改库存或过期() throws Exception {
        when(medPlanMapper.selectOne(any()))
                .thenReturn(plan("p-1001", ELDER_ID));
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "Asia/Shanghai");
        givenMedicine("m-1001");
        givenStock(ELDER_ID, "m-1001", 5, LocalDate.parse("2026-10-01")); // 过期

        mockMvc.perform(put("/api/v1/plans/{planId}", "p-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"times":["08:00"],"items":[{"medicineId":"m-1001"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));

        verify(medPlanMapper, never()).updateById(any(MedPlan.class));
    }

    @Test
    @DisplayName("修改：非监护陌生人 → 40301")
    void 修改非监护() throws Exception {
        when(medPlanMapper.selectOne(any()))
                .thenReturn(plan("p-1001", ELDER_ID));
        // 不置监护关系 → AccessService 判无 WRITE_PLAN

        mockMvc.perform(put("/api/v1/plans/{planId}", "p-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"times":["08:00"],"items":[{"medicineId":"m-1001"}]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(medPlanMapper, never()).updateById(any(MedPlan.class));
    }

    @Test
    @DisplayName("修改：计划不存在 → 40301（防枚举，与详情同口径）")
    void 修改计划不存在() throws Exception {
        when(medPlanMapper.selectOne(any())).thenReturn(null);
        givenGuardianGuards(ELDER_ID);

        mockMvc.perform(put("/api/v1/plans/{planId}", "p-9999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"times":["08:00"],"items":[{"medicineId":"m-1001"}]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ———————————————— 列表 / 详情 ————————————————

    @Test
    @DisplayName("列表：监护人按 elderId 查 → 200，带药品概览")
    void 列表带概览() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medPlanMapper.selectPage(any(), any()))
                .thenAnswer(inv -> {
                    Page<MedPlan> page = inv.getArgument(0);
                    MedPlan plan = new MedPlan();
                    plan.setPlanId("p-1001");
                    plan.setElderId(ELDER_ID);
                    plan.setName("早餐后");
                    plan.setTimes(List.of("08:00"));
                    plan.setStatus("ENABLED");
                    page.setRecords(List.of(plan));
                    page.setTotal(1L);
                    return page;
                });
        when(medPlanItemMapper.selectList(any()))
                .thenReturn(List.of(item("pi-1001", "p-1001", "m-1001")));
        when(medicineMapper.selectList(any()))
                .thenReturn(List.of(medicine("m-1001", "阿莫西林")));

        mockMvc.perform(get("/api/v1/plans").param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].itemCount").value(1))
                .andExpect(jsonPath("$.data.list[0].medicineNames[0]").value("阿莫西林"));
    }

    @Test
    @DisplayName("详情：非监护陌生人 → 40301（防枚举）")
    void 详情非监护() throws Exception {
        when(guardianRelationMapper.selectOne(any()))
                .thenReturn(null);
        when(medPlanMapper.selectOne(any()))
                .thenReturn(plan("p-1001", ELDER_ID));

        mockMvc.perform(get("/api/v1/plans/{planId}", "p-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ———————————————— 启停 / 删除 ————————————————

    @Test
    @DisplayName("启停：监护人 PATCH status=DISABLED → 200")
    void 启停() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medPlanMapper.selectOne(any()))
                .thenReturn(plan("p-1001", ELDER_ID));
        when(medPlanMapper.updateById(any(MedPlan.class))).thenReturn(1);
        when(medPlanItemMapper.selectList(any()))
                .thenReturn(List.of());

        mockMvc.perform(patch("/api/v1/plans/{planId}/status", "p-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    @Test
    @DisplayName("删除：监护人 → 200，级联删明细")
    void 删除级联() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medPlanMapper.selectOne(any()))
                .thenReturn(plan("p-1001", ELDER_ID));
        when(medPlanItemMapper.delete(any())).thenReturn(1);
        when(medPlanMapper.deleteById(any(Long.class))).thenReturn(1);

        mockMvc.perform(delete("/api/v1/plans/{planId}", "p-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(medPlanItemMapper).delete(any());
        verify(medPlanMapper).deleteById(any(Long.class));
    }

    // ———————————————— next-doses 时间线 ————————————————

    @Test
    @DisplayName("next-doses：Asia/Shanghai 时区换算正确，且仅保留未来剂量")
    void 时间线时区换算_上海() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "Asia/Shanghai");
        // 08:00 上海 = 00:00Z = now（恰等于，不算 after）→ 排除；09:00 上海 = 01:00Z → 未来
        when(medPlanMapper.selectOne(any()))
                .thenReturn(planWithTimes("p-1001", ELDER_ID, List.of("08:00", "09:00"), "ENABLED"));
        when(medPlanItemMapper.selectList(any()))
                .thenReturn(List.of(item("pi-1001", "p-1001", "m-1001")));
        when(medicineMapper.selectList(any()))
                .thenReturn(List.of(medicine("m-1001", "阿莫西林")));

        mockMvc.perform(get("/api/v1/plans/{planId}/next-doses", "p-1001").param("days", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].scheduledTimeLocal").value("2026-10-21T09:00"))
                .andExpect(jsonPath("$.data[0].scheduledTimeUtc").value("2026-10-21T09:00:00+08:00"))
                .andExpect(jsonPath("$.data[0].items[0].medicineName").value("阿莫西林"));
    }

    @Test
    @DisplayName("next-doses：时区取自设备（America/New_York），证明非默认上海")
    void 时间线时区取自设备_纽约() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "America/New_York");
        when(medPlanMapper.selectOne(any()))
                .thenReturn(planWithTimes("p-1001", ELDER_ID, List.of("09:00"), "ENABLED"));
        when(medPlanItemMapper.selectList(any()))
                .thenReturn(List.of(item("pi-1001", "p-1001", "m-1001")));
        when(medicineMapper.selectList(any()))
                .thenReturn(List.of(medicine("m-1001", "阿莫西林")));

        // 纽约本地今日 = 2026-10-20（因 now 是 UTC 午夜，纽约慢 12h），故 days=2 才覆盖到 2026-10-21 的 09:00
        // 09:00 NY（EDT -04:00，10 月） = 13:00Z 2026-10-21 → 未来
        mockMvc.perform(get("/api/v1/plans/{planId}/next-doses", "p-1001").param("days", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].scheduledTimeLocal").value("2026-10-21T09:00"))
                .andExpect(jsonPath("$.data[0].scheduledTimeUtc").value("2026-10-21T09:00:00-04:00"))
                .andExpect(jsonPath("$.data[0].items[0].medicineName").value("阿莫西林"));
    }

    @Test
    @DisplayName("next-doses：DISABLED 计划返回空列表")
    void 时间线停用为空() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "Asia/Shanghai");
        when(medPlanMapper.selectOne(any()))
                .thenReturn(planWithTimes("p-1001", ELDER_ID, List.of("09:00"), "DISABLED"));

        mockMvc.perform(get("/api/v1/plans/{planId}/next-doses", "p-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ———————————————— 辅助 ————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private void givenGuardianGuards(String... elderIds) {
        List<GuardianRelation> relations = Arrays.stream(elderIds)
                .map(id -> relation(id, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE))
                .toList();
        when(guardianRelationMapper.selectList(any()))
                .thenReturn(relations);
        when(guardianRelationMapper.selectOne(any()))
                .thenReturn(relations.isEmpty() ? null : relations.get(0));
    }

    private void givenMainDevice(String elderId, String timezone) {
        Device device = new Device();
        device.setDeviceId("BOXA1001");
        device.setElderId(elderId);
        device.setDeviceType(DeviceType.MAIN);
        device.setTimezone(timezone);
        when(deviceMapper.selectOne(any())).thenReturn(device);
    }

    private void givenMedicine(String medicineId) {
        when(medicineMapper.selectOne(any()))
                .thenReturn(medicine(medicineId, "阿莫西林"));
    }

    private void givenStock(String elderId, String medicineId, int quantity, LocalDate expiry) {
        MedicineStock stock = new MedicineStock();
        stock.setElderId(elderId);
        stock.setMedicineId(medicineId);
        stock.setQuantity(quantity);
        stock.setExpiryDate(expiry);
        when(medicineStockMapper.selectList(any()))
                .thenReturn(List.of(stock));
    }

    private static MedPlan plan(String planId, String elderId) {
        MedPlan p = new MedPlan();
        p.setId(1L);
        p.setPlanId(planId);
        p.setElderId(elderId);
        p.setStatus("ENABLED");
        return p;
    }

    private static MedPlan planWithTimes(String planId, String elderId, List<String> times, String status) {
        MedPlan p = plan(planId, elderId);
        p.setTimes(times);
        p.setStatus(status);
        return p;
    }

    private static MedPlanItem item(String itemId, String planId, String medicineId) {
        MedPlanItem it = new MedPlanItem();
        it.setItemId(itemId);
        it.setPlanId(planId);
        it.setMedicineId(medicineId);
        it.setDose("1");
        it.setUnit("片");
        return it;
    }

    private static Medicine medicine(String medicineId, String name) {
        Medicine m = new Medicine();
        m.setMedicineId(medicineId);
        m.setName(name);
        return m;
    }

    private static GuardianRelation relation(String elderId, String guardianId,
                                             RelationRole role, RelationStatus status) {
        GuardianRelation r = new GuardianRelation();
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
        return u;
    }
}

package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
 * 服药记录查询与补录的集成测试（{@code feat/record-core}，见文档 02 第 7 章 / 07）。
 *
 * <p>真实链路：MockMvc → {@code RecordController} → 真实 {@code RecordService} → 真实
 * {@code AccessService} → 打桩的 Mapper（不跑真实 SQL，用内嵌 H2 顶替 DataSource）。
 * 覆盖：列表/详情连通、权限矩阵（老人写 40301 / 陌生人 40301 / PENDING 40302）、记录不存在 40401、
 * confirm 校准（source=MANUAL + 字段覆盖）、confirm 新建补录（返回服务端 mr- 号、planTime 就近槽重算）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecordCoreWebIntegrationTest {

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
    private static final String STRANGER_ID = "u-9999";
    private static final String PLAN_ID = "p-1001";
    private static final String PLAN_ITEM_ID = "pi-1001";
    private static final String MEDICINE_ID = "m-1001";
    private static final String RECORD_ID = "mr-1001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private MedRecordMapper medRecordMapper;
    @MockitoBean
    private MedPlanMapper medPlanMapper;
    @MockitoBean
    private MedicineMapper medicineMapper;
    @MockitoBean
    private DeviceMapper deviceMapper;
    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @BeforeEach
    void setUp() {
        // Mapper 被打桩后 MyBatis 不会为实体建表信息，而 join 名称的 in(...) 要立即解析列名 —— 先预热
        TableInfoWarmup.init(MedRecord.class, MedPlan.class, Medicine.class, User.class,
                GuardianRelation.class, Device.class);
        // AccessService.isElder 用 userMapper 校验目标是否老人：任何查询都返回一位 ELDER
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    // ————————————————— 列表 —————————————————

    @Test
    @DisplayName("列表：FAMILY 监护人 → 200，分页结构正确 + 计划名/药名/hasCapture 已 join")
    void 监护人列表返回并join名称() throws Exception {
        givenGuardianGuards(ELDER_ID);
        MedRecord r = record(RECORD_ID, ELDER_ID, PLAN_ID, MEDICINE_ID, "cap-1",
                OffsetDateTime.parse("2026-10-23T08:05:00+08:00"));
        when(medRecordMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<MedRecord> page = inv.getArgument(0);
                    page.setRecords(List.of(r));
                    page.setTotal(1);
                    return page;
                });
        when(medPlanMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(plan(PLAN_ID, "早餐后")));
        when(medicineMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(medicine(MEDICINE_ID, "二甲双胍")));

        mockMvc.perform(get("/api/v1/records").param("elderId", ELDER_ID)
                        .param("page", "1").param("size", "20")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].recordId").value(RECORD_ID))
                .andExpect(jsonPath("$.data.list[0].planName").value("早餐后"))
                .andExpect(jsonPath("$.data.list[0].medicineName").value("二甲双胍"))
                .andExpect(jsonPath("$.data.list[0].source").value("CAMERA"))
                .andExpect(jsonPath("$.data.list[0].hasCapture").value(true));
    }

    @Test
    @DisplayName("列表：无数据 → 返回空分页（total=0）")
    void 列表无数据返回空() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medRecordMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<MedRecord> page = inv.getArgument(0);
                    page.setRecords(List.of());
                    page.setTotal(0);
                    return page;
                });

        mockMvc.perform(get("/api/v1/records").param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.list.length()").value(0));
    }

    @Test
    @DisplayName("列表：from/to 非法格式 → 40001")
    void 列表时间格式非法() throws Exception {
        givenGuardianGuards(ELDER_ID);

        mockMvc.perform(get("/api/v1/records").param("elderId", ELDER_ID)
                        .param("from", "not-a-date")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ————————————————— 详情 —————————————————

    @Test
    @DisplayName("详情：FAMILY 监护人 → 200，含 captureId/hasCapture")
    void 监护人详情返回() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(record(RECORD_ID, ELDER_ID, PLAN_ID, MEDICINE_ID, "cap-1",
                        OffsetDateTime.parse("2026-10-23T08:05:00+08:00")));
        when(medPlanMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(plan(PLAN_ID, "早餐后")));
        when(medicineMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(medicine(MEDICINE_ID, "二甲双胍")));

        mockMvc.perform(get("/api/v1/records/{recordId}", RECORD_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value(RECORD_ID))
                .andExpect(jsonPath("$.data.captureId").value("cap-1"))
                .andExpect(jsonPath("$.data.hasCapture").value(true))
                .andExpect(jsonPath("$.data.planName").value("早餐后"));
    }

    @Test
    @DisplayName("详情：记录不存在 → 40401")
    void 详情不存在返回_40401() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mockMvc.perform(get("/api/v1/records/{recordId}", "mr-0000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("详情：陌生人（无监护关系）→ 40301")
    void 详情陌生人返回_40301() throws Exception {
        // 不调用 givenGuardianGuards → 无监护关系
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(record(RECORD_ID, ELDER_ID, PLAN_ID, MEDICINE_ID, null, null));

        mockMvc.perform(get("/api/v1/records/{recordId}", RECORD_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ————————————————— confirm：校准已有记录 —————————————————

    @Test
    @DisplayName("confirm 校准：FAMILY 监护人 → 200，source=MANUAL 且覆盖字段生效")
    void 校准已有记录写MANUAL() throws Exception {
        givenGuardianGuards(ELDER_ID);
        MedRecord existing = record(RECORD_ID, ELDER_ID, PLAN_ID, MEDICINE_ID, null,
                OffsetDateTime.parse("2026-10-23T08:05:00+08:00"));
        existing.setActualDose("1");
        existing.setOnTime(true);
        existing.setSource("CAMERA");
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(medRecordMapper.updateById(any(MedRecord.class))).thenReturn(1);
        when(medPlanMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(plan(PLAN_ID, "早餐后")));
        when(medicineMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(medicine(MEDICINE_ID, "二甲双胍")));

        mockMvc.perform(post("/api/v1/records/{recordId}/confirm", RECORD_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actualDose":"2","onTime":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value(RECORD_ID))
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.actualDose").value("2"))
                .andExpect(jsonPath("$.data.onTime").value(false));
    }

    @Test
    @DisplayName("confirm 校准：老人本人（ELDER）无 WRITE_RECORD → 40301")
    void 老人校准返回_40301() throws Exception {
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(record(RECORD_ID, ELDER_ID, PLAN_ID, MEDICINE_ID, null, null));

        mockMvc.perform(post("/api/v1/records/{recordId}/confirm", RECORD_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actualDose":"2"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ————————————————— confirm：新建补录 —————————————————

    @Test
    @DisplayName("confirm 新建补录：记录不存在，FAMILY 监护人 → 200，返回服务端 mr- 号且 planTime 就近槽重算非空")
    void 新建补录返回新号() throws Exception {
        givenGuardianGuards(ELDER_ID);
        AtomicReference<MedRecord> created = new AtomicReference<>();
        when(medRecordMapper.nextRecordId()).thenReturn("mr-2001");
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> created.get() == null ? null : created.get());
        when(medRecordMapper.insert(any(MedRecord.class))).thenAnswer(inv -> {
            created.set(inv.getArgument(0));
            return 1;
        });
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(plan(PLAN_ID, "早餐后"));
        when(medicineMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(medicine(MEDICINE_ID, "二甲双胍"));
        when(medPlanMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(plan(PLAN_ID, "早餐后")));
        when(medicineMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(medicine(MEDICINE_ID, "二甲双胍")));

        mockMvc.perform(post("/api/v1/records/{recordId}/confirm", "mr-9999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","planId":"p-1001","planItemId":"pi-1001",
                                 "medicineId":"m-1001",
                                 "actualTime":"2026-10-23T08:05:00+08:00",
                                 "actualDose":"1","onTime":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value("mr-2001"))
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.elderId").value(ELDER_ID))
                .andExpect(jsonPath("$.data.planTime").exists());
    }

    @Test
    @DisplayName("confirm 新建：缺必填字段（无 medicineId）→ 40001")
    void 新建缺必填返回_40001() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mockMvc.perform(post("/api/v1/records/{recordId}/confirm", "mr-9999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","planId":"p-1001","planItemId":"pi-1001"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("confirm 新建：计划不存在 → 40401")
    void 新建计划不存在返回_40401() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mockMvc.perform(post("/api/v1/records/{recordId}/confirm", "mr-9999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","planId":"p-9999","planItemId":"pi-1001",
                                 "medicineId":"m-1001"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("confirm 新建：监护关系 PENDING → 40302")
    void 新建监护Pending返回_40302() throws Exception {
        givenGuardianGuardsPending(ELDER_ID);
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mockMvc.perform(post("/api/v1/records/{recordId}/confirm", "mr-9999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","planId":"p-1001","planItemId":"pi-1001",
                                 "medicineId":"m-1001"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    /** 配成「监护该老人的 FAMILY 监护人」（ACTIVE）。 */
    private void givenGuardianGuards(String... elderIds) {
        List<GuardianRelation> relations = java.util.Arrays.stream(elderIds)
                .map(id -> relation(id, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE))
                .toList();
        when(guardianRelationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(relations);
        when(guardianRelationMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(relations.isEmpty() ? null : relations.get(0));
    }

    /** 配成「监护该老人的 FAMILY 监护人」（PENDING）。 */
    private void givenGuardianGuardsPending(String... elderIds) {
        List<GuardianRelation> relations = java.util.Arrays.stream(elderIds)
                .map(id -> relation(id, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.PENDING))
                .toList();
        when(guardianRelationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(relations);
        when(guardianRelationMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(relations.isEmpty() ? null : relations.get(0));
    }

    private static MedRecord record(String recordId, String elderId, String planId,
                                    String medicineId, String captureId, OffsetDateTime actualTime) {
        MedRecord r = new MedRecord();
        r.setId(1L);
        r.setRecordId(recordId);
        r.setElderId(elderId);
        r.setPlanId(planId);
        r.setPlanItemId(PLAN_ITEM_ID);
        r.setMedicineId(medicineId);
        r.setCaptureId(captureId);
        r.setActualTime(actualTime);
        r.setActualDose("1");
        r.setOnTime(true);
        r.setSource("CAMERA");
        r.setCreatedAt(OffsetDateTime.parse("2026-10-23T00:00:00Z"));
        return r;
    }

    private static MedPlan plan(String planId, String name) {
        MedPlan p = new MedPlan();
        p.setId(1L);
        p.setPlanId(planId);
        p.setName(name);
        p.setTimes(List.of("08:00", "20:00"));
        p.setElderId(ELDER_ID);
        return p;
    }

    private static Medicine medicine(String medicineId, String name) {
        Medicine m = new Medicine();
        m.setId(1L);
        m.setMedicineId(medicineId);
        m.setName(name);
        return m;
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

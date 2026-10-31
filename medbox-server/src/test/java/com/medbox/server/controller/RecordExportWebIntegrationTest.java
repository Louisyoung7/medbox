package com.medbox.server.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 服药报告导出集成测试（{@code feat/record-report}，见文档 02 第 7 章、07）。
 *
 * <p>真实链路：MockMvc → {@code RecordController#export} → 真实 {@code RecordService} →
 * 打桩的 Mapper。覆盖：CSV 头与行 / Content-Type 与 Content-Disposition / 默认最近 30 天窗口 /
 * 权限矩阵（监护人 200、老人 40301、DOCTOR 监护人 200 验证放开导出）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecordExportWebIntegrationTest {

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
    private static final String MEDICINE_ID = "m-1001";

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
        TableInfoWarmup.init(MedRecord.class, MedPlan.class, Medicine.class, Device.class, User.class,
                GuardianRelation.class);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    @Test
    @DisplayName("导出：FAMILY 监护人 → 200，CSV 含表头与记录行，Content-Type/Disposition 正确")
    void 监护人导出CSV() throws Exception {
        givenGuardianGuards(ELDER_ID, RelationRole.FAMILY);
        stubExportData();

        MvcResult result = mockMvc.perform(get("/api/v1/records/export").param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andReturn();

        String csv = stripBom(result.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("记录ID,老人ID,计划名,药品名称,计划时间,实际时间,是否按时,结论,来源,剂量,抓拍ID,设备ID"),
                "CSV 表头缺失: " + csv);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("mr-1001"), "CSV 应包含记录 mr-1001");
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("二甲双胍"), "CSV 应 join 药品名称");
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("早餐后"), "CSV 应 join 计划名");
    }

    @Test
    @DisplayName("导出：不传 from/to → 默认最近 30 天窗口，仍返回 200 与数据")
    void 默认窗口导出() throws Exception {
        givenGuardianGuards(ELDER_ID, RelationRole.FAMILY);
        stubExportData();

        MvcResult result = mockMvc.perform(get("/api/v1/records/export").param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andReturn();
        String csv = stripBom(result.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("mr-1001"));
    }

    @Test
    @DisplayName("导出：DOCTOR 监护人（放开 EXPORT_RECORD）→ 200，可导出")
    void 医生监护人导出() throws Exception {
        givenGuardianGuards(ELDER_ID, RelationRole.DOCTOR);
        stubExportData();

        mockMvc.perform(get("/api/v1/records/export").param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("导出：老人本人（无 EXPORT_RECORD）→ 40301")
    void 老人导出返回_40301() throws Exception {
        mockMvc.perform(get("/api/v1/records/export").param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("导出：陌生人（无监护关系）→ 40301")
    void 陌生人导出返回_40301() throws Exception {
        mockMvc.perform(get("/api/v1/records/export").param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ————————————————— 辅助 —————————————————

    private void stubExportData() {
        when(medRecordMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(record("mr-1001", ELDER_ID, PLAN_ID, MEDICINE_ID,
                        OffsetDateTime.parse("2026-10-23T08:05:00+08:00"))));
        when(medPlanMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(plan(PLAN_ID, "早餐后")));
        when(medicineMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(medicine(MEDICINE_ID, "二甲双胍")));
    }

    private static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private void givenGuardianGuards(String elderId, RelationRole role) {
        GuardianRelation r = relation(elderId, GUARDIAN_ID, role, RelationStatus.ACTIVE);
        when(guardianRelationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(r));
        when(guardianRelationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(r);
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

    private static MedRecord record(String recordId, String elderId, String planId,
                                   String medicineId, OffsetDateTime actualTime) {
        MedRecord r = new MedRecord();
        r.setId(1L);
        r.setRecordId(recordId);
        r.setElderId(elderId);
        r.setPlanId(planId);
        r.setPlanItemId("pi-1001");
        r.setMedicineId(medicineId);
        r.setCaptureId(null);
        r.setActualTime(actualTime);
        r.setActualDose("1");
        r.setOnTime(true);
        r.setSource("CAMERA");
        r.setDeviceId("d-1");
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

    private static User elder(String userId) {
        User u = new User();
        u.setUserId(userId);
        u.setRole(UserRole.ELDER);
        u.setName("张三");
        return u;
    }
}

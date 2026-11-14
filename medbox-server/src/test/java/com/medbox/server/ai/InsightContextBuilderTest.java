package com.medbox.server.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.AiInsightProperties;
import com.medbox.server.domain.Medicine;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.EnvSampleMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.PlanReminderMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 用药安全上下文构建（05 FP-4）：只读取监护人填写的药品说明。 */
class InsightContextBuilderTest {

    private static final String ELDER_ID = "u-1001";

    private final MedRecordMapper recordMapper = mock(MedRecordMapper.class);
    private final PlanReminderMapper reminderMapper = mock(PlanReminderMapper.class);
    private final AlarmMapper alarmMapper = mock(AlarmMapper.class);
    private final EnvSampleMapper envMapper = mock(EnvSampleMapper.class);
    private final MedicineMapper medicineMapper = mock(MedicineMapper.class);
    private final AiInsightProperties props = new AiInsightProperties();

    private InsightContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new InsightContextBuilder(recordMapper, reminderMapper, alarmMapper,
                envMapper, medicineMapper, props);
    }

    @Test
    @DisplayName("有药品说明 → 拼入名称、用量、说明与 medicine citation")
    void 药品说明进入上下文() {
        when(medicineMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                medicine("m-1001", "阿莫西林", "1", "粒", "主要成分：阿莫西林。禁忌：对青霉素过敏者禁用。")));

        InsightContextBuilder.InsightContext ctx = builder.build(ELDER_ID,
                InsightType.MEDICATION_SAFETY, OffsetDateTime.now().minusDays(7), OffsetDateTime.now());

        assertFalse(ctx.empty());
        assertTrue(ctx.text().contains("【用药安全】"));
        assertTrue(ctx.text().contains("阿莫西林"));
        assertTrue(ctx.text().contains("参考单次用量 1粒"));
        assertTrue(ctx.text().contains("对青霉素过敏者禁用"));
        assertTrue(ctx.citations().contains("medicine#m-1001"));
    }

    @Test
    @DisplayName("说明为空 → 标注未填写；超长 → 按配置截断")
    void 说明缺失与截断() {
        props.setMaxDescriptionChars(5);
        when(medicineMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                medicine("m-1001", "阿莫西林", null, null, null),
                medicine("m-1002", "二甲双胍", null, null, "这是一个很长的药品说明文本")));

        InsightContextBuilder.InsightContext ctx = builder.build(ELDER_ID,
                InsightType.MEDICATION_SAFETY, OffsetDateTime.now().minusDays(7), OffsetDateTime.now());

        assertTrue(ctx.text().contains("未填写说明"));
        assertTrue(ctx.text().contains("这是一个很…"));
        assertFalse(ctx.text().contains("这是一个很长的药品说明文本"));
    }

    @Test
    @DisplayName("无药品档案 → empty=true，不调用大模型")
    void 无药品_空上下文() {
        when(medicineMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        InsightContextBuilder.InsightContext ctx = builder.build(ELDER_ID,
                InsightType.MEDICATION_SAFETY, OffsetDateTime.now().minusDays(7), OffsetDateTime.now());

        assertTrue(ctx.empty());
        assertEquals(List.of(), ctx.citations());
    }

    private static Medicine medicine(String medicineId, String name, String dose, String unit, String description) {
        Medicine m = new Medicine();
        m.setMedicineId(medicineId);
        m.setName(name);
        m.setDosePerTime(dose);
        m.setUnit(unit);
        m.setDescription(description);
        return m;
    }
}

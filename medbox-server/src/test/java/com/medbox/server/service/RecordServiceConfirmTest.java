package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.dto.record.RecordConfirmRequest;
import com.medbox.server.support.TableInfoWarmup;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedicineMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link RecordService#confirm} 的事后补扣（11 §1 规则5 step②）单测：
 * 把 UNCERTAIN / 新建记录改写为 TAKEN 时扣库存，且对已 TAKEN 幂等不重复扣。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordServiceConfirmTest {

    private static final String RECORD_ID = "mr-1001";
    private static final String ELDER_ID = "u-1001";
    private static final String MAIN_ID = "BOXA1001";
    private static final String PLAN_ID = "p-1001";
    private static final String ITEM_ID = "pi-1001";
    private static final String MED_ID = "m-205";

    @Mock private MedRecordMapper medRecordMapper;
    @Mock private MedPlanMapper medPlanMapper;
    @Mock private MedicineMapper medicineMapper;
    @Mock private DeviceMapper deviceMapper;
    @Mock private DeviceTimezoneResolver timezoneResolver;
    @Spy private PlanSlotResolver slotResolver = new PlanSlotResolver();
    @Mock private AccessService accessService;
    @Mock private MedicineStockService medicineStockService;

    private RecordService service;

    @BeforeEach
    void setUp() {
        // 预热 MyBatis-Plus 表信息缓存：detail() 内的 planNames() 用 .in(...) 会立即解析列名
        TableInfoWarmup.init(MedRecord.class, MedPlan.class, Medicine.class, Device.class);
        service = new RecordService(medRecordMapper, medPlanMapper, medicineMapper, deviceMapper,
                timezoneResolver, slotResolver, accessService, medicineStockService, Clock.systemUTC());
        when(medicineStockService.deduct(anyString(), anyString(), anyInt())).thenReturn(1);
        when(timezoneResolver.resolve(anyString())).thenReturn(ZoneId.of("Asia/Shanghai"));
    }

    private MedRecord existing(String faceResult) {
        MedRecord r = new MedRecord();
        r.setRecordId(RECORD_ID);
        r.setElderId(ELDER_ID);
        r.setPlanId(PLAN_ID);
        r.setPlanItemId(ITEM_ID);
        r.setMedicineId(MED_ID);
        r.setActualDose("1");
        r.setDeviceId(MAIN_ID);
        r.setFaceResult(faceResult);
        r.setCreatedAt(OffsetDateTime.parse("2026-10-23T00:00:00Z"));
        return r;
    }

    @Test
    @DisplayName("校准：UNCERTAIN → TAKEN 改写时事后补扣库存一次")
    void uncertainToTakenDeducts() {
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing("UNCERTAIN"));
        when(medRecordMapper.updateById(any(MedRecord.class))).thenReturn(1);

        service.confirm(RECORD_ID, new RecordConfirmRequest(ELDER_ID, PLAN_ID, ITEM_ID, MED_ID,
                OffsetDateTime.parse("2026-10-23T00:05:00Z"), "1", null, "TAKEN"));

        verify(medicineStockService, times(1)).deduct(MAIN_ID, MED_ID, 1);
    }

    @Test
    @DisplayName("幂等：已是 TAKEN 再次确认 TAKEN 不重复扣库存")
    void alreadyTakenNoRedundantDeduct() {
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing("TAKEN"));
        when(medRecordMapper.updateById(any(MedRecord.class))).thenReturn(1);

        service.confirm(RECORD_ID, new RecordConfirmRequest(ELDER_ID, PLAN_ID, ITEM_ID, MED_ID,
                OffsetDateTime.parse("2026-10-23T00:05:00Z"), "1", null, "TAKEN"));

        verify(medicineStockService, never()).deduct(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("校准：UNCERTAIN → ABSENT 不扣库存")
    void uncertainToAbsentNoDeduct() {
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing("UNCERTAIN"));
        when(medRecordMapper.updateById(any(MedRecord.class))).thenReturn(1);

        service.confirm(RECORD_ID, new RecordConfirmRequest(ELDER_ID, PLAN_ID, ITEM_ID, MED_ID,
                OffsetDateTime.parse("2026-10-23T00:05:00Z"), "1", null, "ABSENT"));

        verify(medicineStockService, never()).deduct(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("新建补录为 TAKEN：直接扣库存")
    void manualCreateTakenDeducts() {
        MedRecord created = new MedRecord();
        created.setRecordId("mr-2001");
        created.setPlanId(PLAN_ID);
        created.setFaceResult("TAKEN");
        created.setMedicineId(MED_ID);
        created.setDeviceId(MAIN_ID);
        created.setActualDose("2");
        // 第一次 selectOne（confirm 查已有）= null → 走新建；第二次（detail 回查）返回已建记录
        when(medRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null, created);
        when(medRecordMapper.nextRecordId()).thenReturn("mr-2001");
        when(medRecordMapper.insert(any(MedRecord.class))).thenReturn(1);

        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(ELDER_ID);
        plan.setTimes(List.of("08:00"));
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(plan);
        when(medicineMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new Medicine());

        Device main = new Device();
        main.setDeviceId(MAIN_ID);
        main.setDeviceType(DeviceType.MAIN);
        main.setElderId(ELDER_ID);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(main);

        service.confirm(RECORD_ID, new RecordConfirmRequest(ELDER_ID, PLAN_ID, ITEM_ID, MED_ID,
                OffsetDateTime.parse("2026-10-23T00:05:00Z"), "2", null, "TAKEN"));

        ArgumentCaptor<Integer> delta = ArgumentCaptor.forClass(Integer.class);
        verify(medicineStockService, times(1)).deduct(eq(MAIN_ID), eq(MED_ID), delta.capture());
        assertThat(delta.getValue()).isEqualTo(2);
    }
}

package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedicineStockMapper;
import com.medbox.server.mqtt.payload.DispenseEventPayload;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link DispenseConsumeService} 的纯单测：Mapper 全 mock，覆盖规则表（06 §2.7）的判定分支。
 *
 * <p>注：第二层幂等（唯一索引冲突回滚）依赖真实事务上下文，放到
 * {@code DispenseConsumeIntegrationTest}（@SpringBootTest + H2）里验；本类不触事务。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispenseConsumeServiceTest {

    private static final String DEVICE_ID = "BOXA1001";
    private static final String ELDER_ID = "u-1001";
    private static final String PLAN_ID = "p-1001";
    private static final String ITEM_ID = "pi-1001";
    private static final String MED_ID = "m-205";
    private static final String OTHER_MED_ID = "m-999";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock private MedRecordMapper medRecordMapper;
    @Mock private MedPlanMapper medPlanMapper;
    @Mock private MedPlanItemMapper medPlanItemMapper;
    @Mock private DeviceMapper deviceMapper;
    @Mock private MedicineStockMapper medicineStockMapper;
    @Mock private DeviceTimezoneResolver timezoneResolver;
    @Mock private MissToleranceResolver toleranceResolver;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private DispenseConsumeService service;

    @BeforeEach
    void setUp() {
        when(medRecordMapper.nextRecordId()).thenReturn("mr-1001");
        when(timezoneResolver.resolve(ELDER_ID)).thenReturn(ZONE);
        when(toleranceResolver.resolveMinutes(any(), anyString())).thenReturn(15);
        when(medicineStockMapper.deduct(anyString(), anyString(), anyInt())).thenReturn(1);
    }

    private DispenseEventPayload payload(String ts, String medicineId, String source, Boolean wrong) {
        DispenseEventPayload p = new DispenseEventPayload();
        p.setMsgId("BOXA1001-0000000200");
        p.setTs(ts == null ? null : Instant.parse(ts).toEpochMilli());
        p.setPlanId(PLAN_ID);
        p.setPlanItemId(ITEM_ID);
        p.setMedicineId(medicineId);
        p.setActualDose("1");
        p.setUnit("片");
        p.setWrongDrug(wrong);
        p.setSource(source);
        p.setImageId("cap-1");
        p.setConfidence(0.93);
        return p;
    }

    private void stubOkChain() {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setDeviceType(DeviceType.MAIN);
        device.setElderId(ELDER_ID);
        device.setTimezone("Asia/Shanghai");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);

        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(ELDER_ID);
        plan.setTimes(List.of("08:00"));
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(plan);

        MedPlanItem item = new MedPlanItem();
        item.setItemId(ITEM_ID);
        item.setPlanId(PLAN_ID);
        item.setMedicineId(MED_ID);
        when(medPlanItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(item);
    }

    @Test
    @DisplayName("正常：提前 5 分钟吃 → onTime=true，扣库存按识别药品，发布事件")
    void normalOnTime() {
        stubOkChain();
        // 本地 08:05 Asia/Shanghai = 00:05Z
        Optional<MedRecord> result = service.consume(DEVICE_ID, payload("2026-10-23T00:05:00Z", MED_ID, "CAMERA", false));

        assertThat(result).isPresent();
        MedRecord rec = result.get();
        assertThat(rec.getOnTime()).isTrue();
        assertThat(rec.getPlanTime().toInstant()).isEqualTo(Instant.parse("2026-10-23T00:00:00Z")); // 本地 08:00 = 00:00Z
        assertThat(rec.getMedicineId()).isEqualTo(MED_ID);
        assertThat(rec.getSource()).isEqualTo("CAMERA");
        assertThat(rec.getWrongDrug()).isFalse();

        verify(medRecordMapper).insert(any(MedRecord.class));
        verify(medicineStockMapper).deduct(DEVICE_ID, MED_ID, 1);
        verify(eventPublisher).publishEvent(any(DispenseRecordedEvent.class));
    }

    @Test
    @DisplayName("迟到超过容忍：本地 09:00（08:00+15min 之后）→ onTime=false，仍落库仍扣库存")
    void lateBeyondTolerance() {
        stubOkChain();
        // 本地 09:00 Asia/Shanghai = 01:00Z，超过 08:00+15min(=00:15Z)
        Optional<MedRecord> result = service.consume(DEVICE_ID, payload("2026-10-23T01:00:00Z", MED_ID, "CAMERA", false));

        assertThat(result).isPresent();
        assertThat(result.get().getOnTime()).isFalse();
        verify(medRecordMapper).insert(any(MedRecord.class));
        verify(medicineStockMapper).deduct(DEVICE_ID, MED_ID, 1);
    }

    @Test
    @DisplayName("就近槽跨天：本地 2026-10-21T14:00 比 D 当天 08:00 更靠近 D-1 的 08:00（差 6h vs 18h）")
    void nearestSlotCrossDay() {
        stubOkChain();
        // 本地 2026-10-21T14:00 Asia/Shanghai = 2026-10-21T06:00Z
        Optional<MedRecord> result = service.consume(DEVICE_ID, payload("2026-10-21T06:00:00Z", MED_ID, "CAMERA", false));
        assertThat(result).isPresent();
        // 选中的 plan_time 应为 2026-10-21 本地 08:00 = 2026-10-21T00:00Z
        assertThat(result.get().getPlanTime().toInstant()).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));
    }

    @Test
    @DisplayName("plan_time 为空（times 为空）→ onTime=null，仍落库")
    void noTimesPlanTimeNull() {
        stubOkChain();
        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(ELDER_ID);
        plan.setTimes(List.of());
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(plan);

        Optional<MedRecord> result = service.consume(DEVICE_ID, payload("2026-10-23T00:05:00Z", MED_ID, "CAMERA", false));
        assertThat(result).isPresent();
        assertThat(result.get().getPlanTime()).isNull();
        assertThat(result.get().getOnTime()).isNull();
    }

    @Test
    @DisplayName("错服（wrongDrug=true）：照样落库并扣'识别到的药'，medicineId 取摄像头结果")
    void wrongDrugStillDeducts() {
        stubOkChain();
        // 识别到的药是 m-999（不是计划药品 m-205）
        Optional<MedRecord> result = service.consume(DEVICE_ID, payload("2026-10-23T00:05:00Z", OTHER_MED_ID, "CAMERA", true));

        assertThat(result).isPresent();
        assertThat(result.get().getWrongDrug()).isTrue();
        assertThat(result.get().getMedicineId()).isEqualTo(OTHER_MED_ID);
        verify(medicineStockMapper).deduct(DEVICE_ID, OTHER_MED_ID, 1);
    }

    @Test
    @DisplayName("actualDose 解析：'2' 扣 2；null/空 扣 1")
    void doseParsing() {
        stubOkChain();
        DispenseEventPayload p = payload("2026-10-23T00:05:00Z", MED_ID, "CAMERA", false);
        p.setActualDose("2");
        service.consume(DEVICE_ID, p);
        verify(medicineStockMapper).deduct(DEVICE_ID, MED_ID, 2);

        DispenseEventPayload p2 = payload("2026-10-23T00:05:00Z", MED_ID, "CAMERA", false);
        p2.setActualDose(null);
        service.consume(DEVICE_ID, p2);
        verify(medicineStockMapper).deduct(DEVICE_ID, MED_ID, 1);
    }

    @Test
    @DisplayName("source 缺省回退 CAMERA；MANUAL 透传")
    void sourceDefaultAndManual() {
        stubOkChain();
        Optional<MedRecord> def = service.consume(DEVICE_ID, payload("2026-10-23T00:05:00Z", MED_ID, null, false));
        assertThat(def.get().getSource()).isEqualTo("CAMERA");

        Optional<MedRecord> manual = service.consume(DEVICE_ID, payload("2026-10-23T00:05:00Z", MED_ID, "MANUAL", false));
        assertThat(manual.get().getSource()).isEqualTo("MANUAL");
    }

    // ————————————————— 准入丢弃分支 —————————————————

    @Test
    @DisplayName("丢弃：planItemId 为空")
    void dropMissingPlanItemId() {
        DispenseEventPayload p = payload("2026-10-23T00:05:00Z", MED_ID, "CAMERA", false);
        p.setPlanItemId(null);
        assertThat(service.consume(DEVICE_ID, p)).isEmpty();
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
        verify(medicineStockMapper, never()).deduct(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("丢弃：设备不存在")
    void dropDeviceNotFound() {
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThat(service.consume(DEVICE_ID, payload("2026-10-23T00:05:00Z", MED_ID, "CAMERA", false))).isEmpty();
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
    }

    @Test
    @DisplayName("丢弃：计划不存在")
    void dropPlanNotFound() {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setElderId(ELDER_ID);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThat(service.consume(DEVICE_ID, payload("2026-10-23T00:05:00Z", MED_ID, "CAMERA", false))).isEmpty();
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
    }

    @Test
    @DisplayName("丢弃：计划与设备归属老人不一致（防跨老人伪造）")
    void dropElderMismatch() {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setElderId("u-OTHER");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);
        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(ELDER_ID);
        plan.setTimes(List.of("08:00"));
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(plan);
        assertThat(service.consume(DEVICE_ID, payload("2026-10-23T00:05:00Z", MED_ID, "CAMERA", false))).isEmpty();
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
    }

    @Test
    @DisplayName("丢弃：planItem 不存在或不属于该计划")
    void dropItemNotFound() {
        stubOkChain();
        when(medPlanItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThat(service.consume(DEVICE_ID, payload("2026-10-23T00:05:00Z", MED_ID, "CAMERA", false))).isEmpty();
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
    }
}

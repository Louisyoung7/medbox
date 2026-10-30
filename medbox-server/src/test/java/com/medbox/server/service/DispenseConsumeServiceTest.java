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
import com.medbox.server.config.CaptureProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.dto.capture.CaptureDispenseInput;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.service.DispenseConsumeService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link DispenseConsumeService} 的纯单测：Mapper / MedicineStockService / CaptureProperties 全 mock，
 * 覆盖规则表（06 §2.7）与摄像头三态结论（11 §1 规则5）的判定分支。
 *
 * <p>注：第二层幂等（唯一索引冲突回滚）依赖真实事务上下文，放到
 * {@code DispenseConsumeIntegrationTest}（@SpringBootTest + H2）里验；本类不触事务。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispenseConsumeServiceTest {

    private static final String CAMERA_ID = "CAM01";
    private static final String MAIN_ID = "BOXA1001";
    private static final String ELDER_ID = "u-1001";
    private static final String PLAN_ID = "p-1001";
    private static final String ITEM_ID = "pi-1001";
    private static final String MED_ID = "m-205";
    private static final String OTHER_MED_ID = "m-999";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal THRESHOLD = new BigDecimal("0.6");

    @Mock private MedRecordMapper medRecordMapper;
    @Mock private MedPlanMapper medPlanMapper;
    @Mock private MedPlanItemMapper medPlanItemMapper;
    @Mock private DeviceMapper deviceMapper;
    @Mock private MedicineStockService medicineStockService;
    @Mock private DeviceTimezoneResolver timezoneResolver;
    @Mock private MissToleranceResolver toleranceResolver;
    @Spy private PlanSlotResolver planSlotResolver = new PlanSlotResolver();
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CaptureProperties captureProperties;

    @InjectMocks private DispenseConsumeService service;

    @BeforeEach
    void setUp() {
        when(medRecordMapper.nextRecordId()).thenReturn("mr-1001");
        when(timezoneResolver.resolve(ELDER_ID)).thenReturn(ZONE);
        when(toleranceResolver.resolveMinutes(any(), anyString())).thenReturn(15);
        when(medicineStockService.deduct(anyString(), anyString(), anyInt())).thenReturn(1);
        when(captureProperties.getConfidenceThreshold()).thenReturn(THRESHOLD);
    }

    private CaptureDispenseInput input(String isoInstant, String medicineId, String source,
                                       String faceResult, BigDecimal confidence) {
        CaptureDispenseInput i = new CaptureDispenseInput();
        i.setCameraDeviceId(CAMERA_ID);
        i.setCaptureId("cap-1");
        i.setPlanId(PLAN_ID);
        i.setPlanItemId(ITEM_ID);
        i.setMedicineId(medicineId);
        i.setActualDose("1");
        i.setUnit("片");
        i.setSource(source);
        i.setFaceResult(faceResult);
        i.setConfidence(confidence);
        i.setCapturedAt(OffsetDateTime.parse(isoInstant));
        return i;
    }

    /** 摄像头子设备挂在 MAIN 下（parent_device_id = MAIN），MAIN 与摄像头查出来都是同一设备对象。 */
    private void stubOkChain() {
        Device device = new Device();
        device.setDeviceId(MAIN_ID);
        device.setDeviceType(DeviceType.MAIN);
        device.setParentDeviceId(MAIN_ID);
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
    @DisplayName("正常 TAKEN 高置信度：onTime=true，扣库存按识别药品，发布事件")
    void normalTakenOnTime() {
        stubOkChain();
        // 本地 08:05 Asia/Shanghai = 00:05Z
        Optional<MedRecord> result = service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.93")));

        assertThat(result).isPresent();
        MedRecord rec = result.get();
        assertThat(rec.getOnTime()).isTrue();
        assertThat(rec.getPlanTime().toInstant()).isEqualTo(Instant.parse("2026-10-23T00:00:00Z"));
        assertThat(rec.getMedicineId()).isEqualTo(MED_ID);
        assertThat(rec.getSource()).isEqualTo("CAMERA");
        assertThat(rec.getFaceResult()).isEqualTo("TAKEN");
        assertThat(rec.getConfidence()).isEqualByComparingTo("0.93");
        assertThat(rec.getDeviceId()).isEqualTo(MAIN_ID);

        verify(medRecordMapper).insert(any(MedRecord.class));
        verify(medicineStockService).deduct(MAIN_ID, MED_ID, 1);
        verify(eventPublisher).publishEvent(any(DispenseRecordedEvent.class));
    }

    @Test
    @DisplayName("迟到超过容忍：本地 09:00（08:00+15min 之后）→ onTime=false，仍落库仍扣库存")
    void lateBeyondTolerance() {
        stubOkChain();
        Optional<MedRecord> result = service.consume(input("2026-10-23T01:00:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9")));
        assertThat(result).isPresent();
        assertThat(result.get().getOnTime()).isFalse();
        verify(medRecordMapper).insert(any(MedRecord.class));
        verify(medicineStockService).deduct(MAIN_ID, MED_ID, 1);
    }

    @Test
    @DisplayName("就近槽跨天：本地 2026-10-21T14:00 比 D 当天 08:00 更靠近 D-1 的 08:00（差 6h vs 18h）")
    void nearestSlotCrossDay() {
        stubOkChain();
        Optional<MedRecord> result = service.consume(input("2026-10-21T06:00:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9")));
        assertThat(result).isPresent();
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

        Optional<MedRecord> result = service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9")));
        assertThat(result).isPresent();
        assertThat(result.get().getPlanTime()).isNull();
        assertThat(result.get().getOnTime()).isNull();
    }

    @Test
    @DisplayName("识别到非计划药：照样落库并扣'识别到的药'，medicineId 取摄像头结果")
    void cameraMedicineStillDeducts() {
        stubOkChain();
        Optional<MedRecord> result = service.consume(input("2026-10-23T00:05:00Z", OTHER_MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9")));
        assertThat(result).isPresent();
        assertThat(result.get().getMedicineId()).isEqualTo(OTHER_MED_ID);
        verify(medicineStockService).deduct(MAIN_ID, OTHER_MED_ID, 1);
    }

    @Test
    @DisplayName("actualDose 解析：'2' 扣 2；null/空 扣 1")
    void doseParsing() {
        stubOkChain();
        CaptureDispenseInput p = input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9"));
        p.setActualDose("2");
        service.consume(p);
        verify(medicineStockService).deduct(MAIN_ID, MED_ID, 2);

        CaptureDispenseInput p2 = input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9"));
        p2.setActualDose(null);
        service.consume(p2);
        verify(medicineStockService).deduct(MAIN_ID, MED_ID, 1);
    }

    @Test
    @DisplayName("source 缺省回退 CAMERA；MANUAL 透传")
    void sourceDefaultAndManual() {
        stubOkChain();
        Optional<MedRecord> def = service.consume(input("2026-10-23T00:05:00Z", MED_ID, null, "TAKEN", new BigDecimal("0.9")));
        assertThat(def.get().getSource()).isEqualTo("CAMERA");

        Optional<MedRecord> manual = service.consume(input("2026-10-23T00:05:00Z", MED_ID, "MANUAL", "TAKEN", new BigDecimal("0.9")));
        assertThat(manual.get().getSource()).isEqualTo("MANUAL");
    }

    // ————————————————— faceResult / 阈值分支 —————————————————

    @Test
    @DisplayName("阈值：TAKEN 但 confidence<0.6 → 记 UNCERTAIN，不扣库存")
    void lowConfidenceTakenBecomesUncertain() {
        stubOkChain();
        Optional<MedRecord> result = service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.3")));
        assertThat(result).isPresent();
        assertThat(result.get().getFaceResult()).isEqualTo("UNCERTAIN");
        verify(medicineStockService, never()).deduct(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("摄像头直报 UNCERTAIN → 记 UNCERTAIN，不扣库存")
    void reportedUncertain() {
        stubOkChain();
        Optional<MedRecord> result = service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "UNCERTAIN", new BigDecimal("0.95")));
        assertThat(result).isPresent();
        assertThat(result.get().getFaceResult()).isEqualTo("UNCERTAIN");
        verify(medicineStockService, never()).deduct(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("ABSENT 高置信度 → 记 ABSENT，不扣库存")
    void absentHighConfidence() {
        stubOkChain();
        Optional<MedRecord> result = service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "ABSENT", new BigDecimal("0.95")));
        assertThat(result).isPresent();
        assertThat(result.get().getFaceResult()).isEqualTo("ABSENT");
        verify(medicineStockService, never()).deduct(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("faceResult 缺失（容错）→ UNCERTAIN，不扣库存")
    void missingFaceResultBecomesUncertain() {
        stubOkChain();
        Optional<MedRecord> result = service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", null, new BigDecimal("0.95")));
        assertThat(result).isPresent();
        assertThat(result.get().getFaceResult()).isEqualTo("UNCERTAIN");
        verify(medicineStockService, never()).deduct(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("阈值边界：confidence==0.6（等于阈值）不降级，记 TAKEN 并扣库存")
    void thresholdBoundaryTaken() {
        stubOkChain();
        Optional<MedRecord> result = service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.6")));
        assertThat(result).isPresent();
        assertThat(result.get().getFaceResult()).isEqualTo("TAKEN");
        verify(medicineStockService).deduct(MAIN_ID, MED_ID, 1);
    }

    // ————————————————— resolveFaceResult 纯函数 —————————————————

    @Test
    @DisplayName("resolveFaceResult：各分支")
    void resolveFaceResultBranches() {
        assertThat(DispenseConsumeService.resolveFaceResult(null, null, THRESHOLD)).isEqualTo("UNCERTAIN");
        assertThat(DispenseConsumeService.resolveFaceResult("BOGUS", new BigDecimal("0.9"), THRESHOLD)).isEqualTo("UNCERTAIN");
        assertThat(DispenseConsumeService.resolveFaceResult("UNCERTAIN", new BigDecimal("0.9"), THRESHOLD)).isEqualTo("UNCERTAIN");
        assertThat(DispenseConsumeService.resolveFaceResult("TAKEN", new BigDecimal("0.3"), THRESHOLD)).isEqualTo("UNCERTAIN");
        assertThat(DispenseConsumeService.resolveFaceResult("TAKEN", new BigDecimal("0.6"), THRESHOLD)).isEqualTo("TAKEN");
        assertThat(DispenseConsumeService.resolveFaceResult("ABSENT", new BigDecimal("0.9"), THRESHOLD)).isEqualTo("ABSENT");
        // confidence 缺失时不降级
        assertThat(DispenseConsumeService.resolveFaceResult("TAKEN", null, THRESHOLD)).isEqualTo("TAKEN");
    }

    // ————————————————— 准入丢弃分支 —————————————————

    @Test
    @DisplayName("丢弃：planItemId 为空")
    void dropMissingPlanItemId() {
        CaptureDispenseInput i = input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9"));
        i.setPlanItemId(null);
        assertThat(service.consume(i)).isEmpty();
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
        verify(medicineStockService, never()).deduct(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("丢弃：摄像头设备不存在")
    void dropCameraNotFound() {
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThat(service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9")))).isEmpty();
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
    }

    @Test
    @DisplayName("丢弃：MAIN 药箱不存在或无归属老人")
    void dropMainNotFound() {
        Device camera = new Device();
        camera.setDeviceId(CAMERA_ID);
        camera.setParentDeviceId(MAIN_ID);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(camera);
        assertThat(service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9")))).isEmpty();
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
    }

    @Test
    @DisplayName("丢弃：计划不存在")
    void dropPlanNotFound() {
        Device device = new Device();
        device.setDeviceId(MAIN_ID);
        device.setParentDeviceId(MAIN_ID);
        device.setElderId(ELDER_ID);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThat(service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9")))).isEmpty();
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
    }

    @Test
    @DisplayName("丢弃：计划与药箱归属老人不一致（防跨老人伪造）")
    void dropElderMismatch() {
        Device device = new Device();
        device.setDeviceId(MAIN_ID);
        device.setParentDeviceId(MAIN_ID);
        device.setElderId("u-OTHER");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);
        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(ELDER_ID);
        plan.setTimes(List.of("08:00"));
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(plan);
        assertThat(service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9")))).isEmpty();
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
    }

    @Test
    @DisplayName("丢弃：planItem 不存在或不属于该计划")
    void dropItemNotFound() {
        stubOkChain();
        when(medPlanItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThat(service.consume(input("2026-10-23T00:05:00Z", MED_ID, "CAMERA", "TAKEN", new BigDecimal("0.9")))).isEmpty();
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
    }
}

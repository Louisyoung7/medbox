package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.CaptureProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.dto.capture.CaptureDispenseInput;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 服药事件消费核心（feat/dispense-consume → feat/backend-capture-event 改造）。
 *
 * <p>原入口为 MQTT {@code up/event/dispense}（{@code DispenseUpMessageHandler}，本期下线）；
 * 现统一由摄像头经 {@code capture-core} 的 HTTP 上传接口上报，组装成 {@link CaptureDispenseInput}
 * 后转发本类。判定逻辑（就近槽 / 三级容忍 / 库存扣减）口径保持不变。
 *
 * <p>消费流程：准入校验 → 由摄像头子设备 {@code parent_device_id} 反查 MAIN 药箱 → 时区 + 就近槽算
 * {@code plan_time} → 三级容忍算 {@code onTime}（单侧窗口，提前恒 true 只判迟到）→ 据置信度阈值把
 * 低置信度 / UNCERTAIN 结论标为待确认 → 写 {@code med_record}（唯一索引冲突回滚）→ 仅 {@code TAKEN}
 * 扣库存 → 发布 {@link DispenseRecordedEvent}（事务提交后派发）。
 *
 * <p><b>msgId 第一层幂等已由网关完成</b>；第二层幂等是 {@code med_record} 上的部分唯一索引
 * {@code uk_med_record_item_plan_time}。
 *
 * <p><b>非 Web 线程</b>：本服务在 HTTP 请求线程被调用，没有 {@code AuthContext}，一律走 Mapper
 * 直接查归属（与文档 02 §1.3.1 一致），不调 {@code AccessService}。
 *
 * <p>规则表细节沉淀在文档 06 §2.7 / 11 §1。
 */
@Service
public class DispenseConsumeService {

    private static final Logger log = LoggerFactory.getLogger(DispenseConsumeService.class);
    private static final String DEFAULT_SOURCE = "CAMERA";
    private static final Set<String> KNOWN_FACE_RESULTS = Set.of("TAKEN", "ABSENT", "UNCERTAIN");

    private final MedRecordMapper medRecordMapper;
    private final MedPlanMapper medPlanMapper;
    private final MedPlanItemMapper medPlanItemMapper;
    private final DeviceMapper deviceMapper;
    private final MedicineStockService medicineStockService;
    private final DeviceTimezoneResolver timezoneResolver;
    private final MissToleranceResolver toleranceResolver;
    private final PlanSlotResolver planSlotResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final CaptureProperties captureProperties;
    private final Clock clock;

    /**
     * 唯一构造（Spring 与 Mockito 共用）：无 {@code Clock} Bean，默认 {@code systemUTC()}。
     */
    public DispenseConsumeService(MedRecordMapper medRecordMapper, MedPlanMapper medPlanMapper,
                                  MedPlanItemMapper medPlanItemMapper, DeviceMapper deviceMapper,
                                  MedicineStockService medicineStockService, DeviceTimezoneResolver timezoneResolver,
                                  MissToleranceResolver toleranceResolver, PlanSlotResolver planSlotResolver,
                                  ApplicationEventPublisher eventPublisher, CaptureProperties captureProperties) {
        this.medRecordMapper = medRecordMapper;
        this.medPlanMapper = medPlanMapper;
        this.medPlanItemMapper = medPlanItemMapper;
        this.deviceMapper = deviceMapper;
        this.medicineStockService = medicineStockService;
        this.timezoneResolver = timezoneResolver;
        this.toleranceResolver = toleranceResolver;
        this.planSlotResolver = planSlotResolver;
        this.eventPublisher = eventPublisher;
        this.captureProperties = captureProperties;
        this.clock = Clock.systemUTC();
    }

    /**
     * 消费一次摄像头上报的服药事件。
     *
     * @return 写库成功的记录；准入被丢弃或幂等命中时为 {@code empty}（调用方无需区分，仅日志有别）
     */
    @Transactional
    public Optional<MedRecord> consume(CaptureDispenseInput input) {
        if (input == null || isBlank(input.getPlanId()) || isBlank(input.getPlanItemId())) {
            log.warn("dispense 丢弃：input 缺失 planId/planItemId camera={} capture={}",
                    input == null ? "<null>" : input.getCameraDeviceId(),
                    input == null ? "<null>" : input.getCaptureId());
            return Optional.empty();
        }

        // —— 1. 由摄像头子设备反查 MAIN 药箱 ——
        Device camera = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, input.getCameraDeviceId()));
        if (camera == null) {
            log.warn("dispense 丢弃：摄像头设备不存在 camera={}", input.getCameraDeviceId());
            return Optional.empty();
        }
        String mainDeviceId = firstNonBlank(camera.getParentDeviceId(), input.getCameraDeviceId());
        Device main = deviceMapper.selectOne(new LambdaQueryWrapper<Device>().eq(Device::getDeviceId, mainDeviceId));
        if (main == null || isBlank(main.getElderId())) {
            log.warn("dispense 丢弃：MAIN 药箱不存在或无归属老人 camera={} main={}",
                    input.getCameraDeviceId(), mainDeviceId);
            return Optional.empty();
        }

        MedPlan plan = medPlanMapper.selectOne(new LambdaQueryWrapper<MedPlan>().eq(MedPlan::getPlanId, input.getPlanId()));
        if (plan == null) {
            log.warn("dispense 丢弃：计划不存在 camera={} planId={}", input.getCameraDeviceId(), input.getPlanId());
            return Optional.empty();
        }
        if (!main.getElderId().equals(plan.getElderId())) {
            log.warn("dispense 丢弃：计划与药箱归属老人不一致 camera={} planId={} mainElder={} planElder={}",
                    input.getCameraDeviceId(), input.getPlanId(), main.getElderId(), plan.getElderId());
            return Optional.empty();
        }
        MedPlanItem item = medPlanItemMapper.selectOne(new LambdaQueryWrapper<MedPlanItem>()
                .eq(MedPlanItem::getItemId, input.getPlanItemId())
                .eq(MedPlanItem::getPlanId, input.getPlanId()));
        if (item == null) {
            log.warn("dispense 丢弃：planItem 不存在或不属于该计划 camera={} planItemId={} planId={}",
                    input.getCameraDeviceId(), input.getPlanItemId(), input.getPlanId());
            return Optional.empty();
        }

        // —— 2. plan_time 就近槽匹配（跨天取 D-1/D/D+1 三天候选）——
        OffsetDateTime actualTime = input.getCapturedAt() != null ? input.getCapturedAt() : OffsetDateTime.now(clock);
        long ts = actualTime.toInstant().toEpochMilli();
        ZoneId zone = timezoneResolver.resolve(main.getElderId());
        OffsetDateTime planTime = nearestSlot(ts, zone, plan.getTimes());

        // —— 3. 三级容忍算 onTime（单侧窗口：提前恒 true，只判迟到）——
        int tolerance = toleranceResolver.resolveMinutes(plan, main.getElderId());
        Boolean onTime = planTime == null ? null : !actualTime.isAfter(planTime.plusMinutes(tolerance));

        // —— 4. faceResult 最终值解析（阈值 / UNCERTAIN 容错）——
        String finalFace = resolveFaceResult(input.getFaceResult(), input.getConfidence(),
                captureProperties.getConfidenceThreshold());

        // —— 5. 组装记录 ——
        MedRecord record = new MedRecord();
        record.setRecordId(medRecordMapper.nextRecordId());
        record.setElderId(main.getElderId());
        record.setPlanId(input.getPlanId());
        record.setPlanItemId(input.getPlanItemId());
        record.setMedicineId(input.getMedicineId());
        record.setCaptureId(input.getCaptureId());
        record.setActualDose(input.getActualDose());
        record.setPlanTime(planTime);
        record.setActualTime(actualTime);
        record.setOnTime(onTime);
        record.setSource(firstNonBlank(input.getSource(), DEFAULT_SOURCE));
        record.setFaceResult(finalFace);
        record.setConfidence(input.getConfidence());
        record.setDeviceId(mainDeviceId);
        record.setCreatedAt(OffsetDateTime.now(clock));

        // —— 6. 写记录（第二层业务幂等：唯一索引冲突 → 回滚跳过）——
        try {
            medRecordMapper.insert(record);
        } catch (DuplicateKeyException ex) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.info("dispense 幂等命中：跳过写记录与扣库存 camera={} planItemId={} planTime={}",
                    input.getCameraDeviceId(), input.getPlanItemId(), planTime);
            return Optional.empty();
        }

        // —— 7. 扣库存（仅 TAKEN；按识别到的药品，不足扣到 0，不阻断）——
        if ("TAKEN".equals(finalFace)) {
            if (isBlank(input.getMedicineId())) {
                log.warn("dispense 未扣库存：medicineId 为空 camera={} capture={}",
                        input.getCameraDeviceId(), input.getCaptureId());
            } else {
                int delta = parseDose(input.getActualDose());
                medicineStockService.deduct(mainDeviceId, input.getMedicineId(), delta);
            }
        }

        // —— 8. 发布领域事件（AFTER_COMMIT 派发）——
        eventPublisher.publishEvent(new DispenseRecordedEvent(
                record.getRecordId(), record.getElderId(), record.getPlanId(), record.getPlanItemId(),
                record.getMedicineId(), record.getPlanTime(), record.getActualTime(), record.getOnTime(),
                input.getConfidence() == null ? null : input.getConfidence().doubleValue(),
                record.getDeviceId(), record.getCaptureId(), finalFace));

        return Optional.of(record);
    }

    // ————————————————— 内部：就近槽 —————————————————

    /**
     * 取与 {@code ts} 时间差最小的计划槽作为 {@code plan_time}。
     * 委托 {@link PlanSlotResolver#expandSlots} 展开 {@code ts} 前后各 1 天的全部槽后取最近者，
     * 与漏服扫描共用同一套"本地墙钟 times → 具体瞬时"的换算（文档 06 §2.6.1）。
     */
    private OffsetDateTime nearestSlot(long ts, ZoneId zone, List<String> times) {
        if (times == null || times.isEmpty()) {
            return null;
        }
        Instant instant = Instant.ofEpochMilli(ts);
        // 以 ts 为中心 ±1 天展开，等价于原实现 D-1/D/D+1 三天候选
        List<OffsetDateTime> candidates = planSlotResolver.expandSlots(
                instant.minus(1, java.time.temporal.ChronoUnit.DAYS),
                instant.plus(1, java.time.temporal.ChronoUnit.DAYS), zone, times);
        OffsetDateTime best = null;
        long bestDiff = Long.MAX_VALUE;
        for (OffsetDateTime slot : candidates) {
            long diff = Math.abs(java.time.Duration.between(slot.toInstant(), instant).toMillis());
            if (diff < bestDiff || (diff == bestDiff && (best == null || slot.isBefore(best)))) {
                bestDiff = diff;
                best = slot;
            }
        }
        return best;
    }

    // ————————————————— 内部：faceResult 解析 —————————————————

    /**
     * 摄像头上报三态结论的最终值解析（纯函数，便于单测）。
     * <ul>
     *   <li>上报为空 / 非法值 → UNCERTAIN（容错，待确认）；</li>
     *   <li>上报 UNCERTAIN → UNCERTAIN；</li>
     *   <li>上报 TAKEN/ABSENT 但 confidence &lt; 阈值 → UNCERTAIN（待监护人复核）；</li>
     *   <li>否则取摄像头原值。</li>
     * </ul>
     */
    static String resolveFaceResult(String reported, BigDecimal confidence, BigDecimal threshold) {
        if (isBlank(reported)) {
            return "UNCERTAIN";
        }
        String r = reported.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_FACE_RESULTS.contains(r)) {
            return "UNCERTAIN";
        }
        if ("UNCERTAIN".equals(r)) {
            return "UNCERTAIN";
        }
        if (confidence != null && threshold != null && confidence.compareTo(threshold) < 0) {
            return "UNCERTAIN";
        }
        return r;
    }

    // ————————————————— 内部：小工具 —————————————————

    private int parseDose(String actualDose) {
        if (isBlank(actualDose)) {
            return 1;
        }
        try {
            int v = (int) Math.floor(Double.parseDouble(actualDose.trim()));
            return v > 0 ? v : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String s, String fallback) {
        return isBlank(s) ? fallback : s;
    }
}

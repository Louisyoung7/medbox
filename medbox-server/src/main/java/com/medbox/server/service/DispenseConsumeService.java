package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedicineStockMapper;
import com.medbox.server.mqtt.payload.DispenseEventPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 服药事件消费核心（feat/dispense-consume，07 清单 P0 第 9 步）。
 *
 * <p>消费设备 {@code up/event/dispense} 上报：准入校验 → 时区 + 就近槽算 {@code plan_time} →
 * 三级容忍算 {@code onTime}（单侧窗口，提前恒 true 只判迟到）→ 写 {@code med_record}
 * （唯一索引冲突回滚）→ 扣库存 → 发布 {@link DispenseRecordedEvent}（事务提交后派发）。
 *
 * <p><b>msgId 第一层幂等已由网关完成</b>（{@code MqttMessageDeduplicator}），本类不重复做；
 * 第二层幂等是 {@code med_record} 上的部分唯一索引 {@code uk_med_record_item_plan_time}。
 *
 * <p><b>非 Web 线程</b>：本服务在 MQTT 消费线程被调用，没有 {@code AuthContext}，一律走 Mapper
 * 直接查归属（与文档 02 §1.3.1 一致），不调 {@code AccessService}。
 *
 * <p>规则表细节沉淀在文档 06 §2.7。
 */
@Service
public class DispenseConsumeService {

    private static final Logger log = LoggerFactory.getLogger(DispenseConsumeService.class);
    private static final String DEFAULT_SOURCE = "CAMERA";

    private final MedRecordMapper medRecordMapper;
    private final MedPlanMapper medPlanMapper;
    private final MedPlanItemMapper medPlanItemMapper;
    private final DeviceMapper deviceMapper;
    private final MedicineStockMapper medicineStockMapper;
    private final DeviceTimezoneResolver timezoneResolver;
    private final MissToleranceResolver toleranceResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * 唯一构造（Spring 与 Mockito 共用）：无 {@code Clock} Bean，默认 {@code systemUTC()}
     * （与 PlanService 同一做法）。测试若需固定时钟，用 {@code reflection} 设置或直接构造后赋值——
     * 本期单测不依赖时钟（payload 始终带 {@code ts}）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public DispenseConsumeService(MedRecordMapper medRecordMapper, MedPlanMapper medPlanMapper,
                                  MedPlanItemMapper medPlanItemMapper, DeviceMapper deviceMapper,
                                  MedicineStockMapper medicineStockMapper, DeviceTimezoneResolver timezoneResolver,
                                  MissToleranceResolver toleranceResolver, ApplicationEventPublisher eventPublisher) {
        this.medRecordMapper = medRecordMapper;
        this.medPlanMapper = medPlanMapper;
        this.medPlanItemMapper = medPlanItemMapper;
        this.deviceMapper = deviceMapper;
        this.medicineStockMapper = medicineStockMapper;
        this.timezoneResolver = timezoneResolver;
        this.toleranceResolver = toleranceResolver;
        this.eventPublisher = eventPublisher;
        this.clock = Clock.systemUTC();
    }

    /**
     * 消费一次服药事件。
     *
     * @return 写库成功的记录；准入被丢弃或幂等命中时为 {@code empty}（调用方无需区分，仅日志有别）
     */
    @Transactional
    public Optional<MedRecord> consume(String deviceId, DispenseEventPayload payload) {
        // —— 1. 准入校验（不通过 → warn 丢弃，不落库）——
        if (payload == null || isBlank(payload.getPlanId()) || isBlank(payload.getPlanItemId())) {
            log.warn("dispense 丢弃：payload 缺失 planId/planItemId deviceId={} msgId={}",
                    deviceId, msgIdOf(payload));
            return Optional.empty();
        }
        Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>().eq(Device::getDeviceId, deviceId));
        if (device == null || isBlank(device.getElderId())) {
            log.warn("dispense 丢弃：设备不存在或无归属老人 deviceId={} msgId={}",
                    deviceId, payload.getMsgId());
            return Optional.empty();
        }
        MedPlan plan = medPlanMapper.selectOne(new LambdaQueryWrapper<MedPlan>().eq(MedPlan::getPlanId, payload.getPlanId()));
        if (plan == null) {
            log.warn("dispense 丢弃：计划不存在 deviceId={} planId={} msgId={}",
                    deviceId, payload.getPlanId(), payload.getMsgId());
            return Optional.empty();
        }
        if (!device.getElderId().equals(plan.getElderId())) {
            log.warn("dispense 丢弃：计划与设备归属老人不一致 deviceId={} planId={} deviceElder={} planElder={}",
                    deviceId, payload.getPlanId(), device.getElderId(), plan.getElderId());
            return Optional.empty();
        }
        MedPlanItem item = medPlanItemMapper.selectOne(new LambdaQueryWrapper<MedPlanItem>()
                .eq(MedPlanItem::getItemId, payload.getPlanItemId())
                .eq(MedPlanItem::getPlanId, payload.getPlanId()));
        if (item == null) {
            log.warn("dispense 丢弃：planItem 不存在或不属于该计划 deviceId={} planItemId={} planId={}",
                    deviceId, payload.getPlanItemId(), payload.getPlanId());
            return Optional.empty();
        }

        // —— 2. plan_time 就近槽匹配（跨天取 D-1/D/D+1 三天候选）——
        long ts = tsOrNow(payload.getTs());
        OffsetDateTime actualTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneOffset.UTC);
        ZoneId zone = timezoneResolver.resolve(device.getElderId());
        OffsetDateTime planTime = nearestSlot(ts, zone, plan.getTimes());

        // —— 3. 三级容忍算 onTime（单侧窗口：提前恒 true，只判迟到）——
        int tolerance = toleranceResolver.resolveMinutes(plan, device.getElderId());
        Boolean onTime = planTime == null ? null : !actualTime.isAfter(planTime.plusMinutes(tolerance));

        // —— 4. 组装记录 ——
        MedRecord record = new MedRecord();
        record.setRecordId(medRecordMapper.nextRecordId());
        record.setElderId(device.getElderId());
        record.setPlanId(payload.getPlanId());
        record.setPlanItemId(payload.getPlanItemId());
        record.setMedicineId(payload.getMedicineId());
        record.setCaptureId(payload.getImageId());
        record.setActualDose(payload.getActualDose());
        record.setPlanTime(planTime);
        record.setActualTime(actualTime);
        record.setOnTime(onTime);
        record.setWrongDrug(payload.getWrongDrug());
        record.setSource(firstNonBlank(payload.getSource(), DEFAULT_SOURCE));
        record.setDeviceId(deviceId);
        record.setCreatedAt(OffsetDateTime.now(clock));

        // —— 5. 写记录（第二层业务幂等：唯一索引冲突 → 回滚跳过）——
        try {
            medRecordMapper.insert(record);
        } catch (DuplicateKeyException ex) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.info("dispense 幂等命中：跳过写记录与扣库存 deviceId={} planItemId={} planTime={} msgId={}",
                    deviceId, payload.getPlanItemId(), planTime, payload.getMsgId());
            return Optional.empty();
        }

        // —— 6. 扣库存（按识别到的药品，不足扣到 0，不阻断；错服也扣）——
        if (isBlank(payload.getMedicineId())) {
            log.warn("dispense 未扣库存：medicineId 为空 deviceId={} msgId={}", deviceId, payload.getMsgId());
        } else {
            int delta = parseDose(payload.getActualDose());
            int affected = medicineStockMapper.deduct(deviceId, payload.getMedicineId(), delta);
            if (affected == 0) {
                log.warn("dispense 扣库存无对应库存行（识别到的药品可能不在该药箱）：deviceId={} medicineId={} msgId={}",
                        deviceId, payload.getMedicineId(), payload.getMsgId());
            }
        }

        // —— 7. 发布领域事件（AFTER_COMMIT 派发）——
        eventPublisher.publishEvent(new DispenseRecordedEvent(
                record.getRecordId(), record.getElderId(), record.getPlanId(), record.getPlanItemId(),
                record.getMedicineId(), record.getPlanTime(), record.getActualTime(), record.getOnTime(),
                record.getWrongDrug(), payload.getConfidence(), record.getDeviceId(), record.getCaptureId()));

        return Optional.of(record);
    }

    // ————————————————— 内部：就近槽 —————————————————

    /**
     * 取与 {@code ts} 时间差最小的计划槽作为 {@code plan_time}。
     * 候选 = {@code ts} 本地日期的 D-1 / D / D+1 三天 × 全部 {@code times}（覆盖跨天边界）；
     * 差值相同时取较早的槽。
     */
    private OffsetDateTime nearestSlot(long ts, ZoneId zone, List<String> times) {
        if (times == null || times.isEmpty()) {
            return null;
        }
        LocalDate base = LocalDate.ofInstant(Instant.ofEpochMilli(ts), zone);
        OffsetDateTime best = null;
        long bestDiff = Long.MAX_VALUE;
        for (int d = -1; d <= 1; d++) {
            LocalDate date = base.plusDays(d);
            for (String time : times) {
                try {
                    LocalTime lt = LocalTime.parse(time);
                    LocalDateTime ldt = LocalDateTime.of(date, lt);
                    OffsetDateTime slot = ZonedDateTime.of(ldt, zone).toOffsetDateTime();
                    long diff = Math.abs(Duration.between(slot.toInstant(), Instant.ofEpochMilli(ts)).toMillis());
                    if (diff < bestDiff || (diff == bestDiff && (best == null || slot.isBefore(best)))) {
                        bestDiff = diff;
                        best = slot;
                    }
                } catch (RuntimeException e) {
                    // 计划里混入了非法时间字符串（理论上 create/update 已校验），跳过该槽
                    log.debug("dispense 跳过非法 times 槽 deviceId time={}：{}", time, e.getMessage());
                }
            }
        }
        return best;
    }

    // ————————————————— 内部：小工具 —————————————————

    private long tsOrNow(Long ts) {
        return (ts != null && ts > 0) ? ts : clock.millis();
    }

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

    private static String msgIdOf(DispenseEventPayload payload) {
        return payload == null ? "<null>" : payload.getMsgId();
    }
}

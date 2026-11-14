package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.dto.capture.CaptureDispenseInput;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * 服药事件消费核心（见 07 §1）。
 *
 * <p>设备经 HTTP captures 上报，组装成 {@link CaptureDispenseInput} 后转发本类。消费流程：
 * 准入校验（{@code planId + cellNo}、格号 1/2/3、设备归属、计划归属）→ 按该格就近槽重算
 * {@code plan_time} → 三级容忍算 {@code onTime}（单侧窗口）→ 取药结论归一为二值 → 写
 * {@code med_record}（唯一索引冲突回滚）→ 发布 {@link DispenseRecordedEvent}。
 *
 * 第二层幂等是 {@code uk_med_record_cell_plan_time (plan_id, cell_no, plan_time)} 部分唯一索引。
 *
 * <p><b>非 Web 线程</b>：本服务无 {@code AuthContext}，一律走 Mapper 直接查归属，不调 {@code AccessService}。
 */
@Service
public class DispenseConsumeService {

    private static final Logger log = LoggerFactory.getLogger(DispenseConsumeService.class);
    private static final String DEFAULT_SOURCE = "CAMERA";
    private static final Set<String> KNOWN_TAKEN_RESULTS = Set.of("TAKEN", "UNTAKEN");

    private final MedRecordMapper medRecordMapper;
    private final MedPlanMapper medPlanMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceTimezoneResolver timezoneResolver;
    private final MissToleranceResolver toleranceResolver;
    private final PlanSlotResolver planSlotResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public DispenseConsumeService(MedRecordMapper medRecordMapper, MedPlanMapper medPlanMapper,
                                  DeviceMapper deviceMapper, DeviceTimezoneResolver timezoneResolver,
                                  MissToleranceResolver toleranceResolver, PlanSlotResolver planSlotResolver,
                                  ApplicationEventPublisher eventPublisher) {
        this.medRecordMapper = medRecordMapper;
        this.medPlanMapper = medPlanMapper;
        this.deviceMapper = deviceMapper;
        this.timezoneResolver = timezoneResolver;
        this.toleranceResolver = toleranceResolver;
        this.planSlotResolver = planSlotResolver;
        this.eventPublisher = eventPublisher;
        this.clock = Clock.systemUTC();
    }

    /**
     * 消费一次设备上报的服药事件。
     *
     * @return 写库成功的记录；准入被丢弃或幂等命中时为 {@code empty}
     */
    @Transactional
    public Optional<MedRecord> consume(CaptureDispenseInput input) {
        if (input == null || isBlank(input.getPlanId()) || !PlanSlotResolver.isValidCellNo(input.getCellNo())) {
            log.warn("dispense 丢弃：input 缺失 planId/cellNo 或格号越界 camera={} capture={}",
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

        MedPlan plan = medPlanMapper.selectOne(
                new LambdaQueryWrapper<MedPlan>().eq(MedPlan::getPlanId, input.getPlanId()));
        if (plan == null) {
            log.warn("dispense 丢弃：计划不存在 camera={} planId={}", input.getCameraDeviceId(), input.getPlanId());
            return Optional.empty();
        }
        if (!main.getElderId().equals(plan.getElderId())) {
            log.warn("dispense 丢弃：计划与药箱归属老人不一致 camera={} planId={} mainElder={} planElder={}",
                    input.getCameraDeviceId(), input.getPlanId(), main.getElderId(), plan.getElderId());
            return Optional.empty();
        }

        // —— 2. 按该格就近槽匹配 plan_time（D-1 / D / D+1）——
        OffsetDateTime actualTime = input.getCapturedAt() != null ? input.getCapturedAt() : OffsetDateTime.now(clock);
        ZoneId zone = timezoneResolver.resolve(main.getElderId());
        OffsetDateTime planTime = planSlotResolver.nearestSlot(
                actualTime.toInstant(), zone, plan.getSlots(), input.getCellNo());

        // —— 3. 三级容忍算 onTime（单侧窗口：提前恒 true，只判迟到）——
        int tolerance = toleranceResolver.resolveMinutes(plan, main.getElderId());
        Boolean onTime = planTime == null ? null : !actualTime.isAfter(planTime.plusMinutes(tolerance));

        // —— 4. takenResult 归一为二值（TAKEN / UNTAKEN）——
        String finalResult = normalizeTakenResult(input.getTakenResult());

        // —— 5. 组装记录 ——
        MedRecord record = new MedRecord();
        record.setRecordId(medRecordMapper.nextRecordId());
        record.setElderId(main.getElderId());
        record.setPlanId(input.getPlanId());
        record.setCellNo(input.getCellNo());
        record.setCaptureId(input.getCaptureId());
        record.setPlanTime(planTime);
        record.setActualTime(actualTime);
        record.setOnTime(onTime);
        record.setSource(firstNonBlank(input.getSource(), DEFAULT_SOURCE));
        record.setTakenResult(finalResult);
        record.setDeviceId(mainDeviceId);
        record.setCreatedAt(OffsetDateTime.now(clock));

        // —— 6. 写记录（第二层业务幂等：唯一索引冲突 → 回滚跳过）——
        try {
            medRecordMapper.insert(record);
        } catch (DuplicateKeyException ex) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.info("dispense 幂等命中：跳过写记录 camera={} planId={} cellNo={} planTime={}",
                    input.getCameraDeviceId(), input.getPlanId(), input.getCellNo(), planTime);
            return Optional.empty();
        }

        // —— 7. 发布领域事件（AFTER_COMMIT 派发）；不推 WS、不生成告警 ——
        eventPublisher.publishEvent(new DispenseRecordedEvent(
                record.getRecordId(), record.getElderId(), record.getPlanId(), record.getCellNo(),
                record.getPlanTime(), record.getActualTime(), record.getOnTime(),
                record.getDeviceId(), record.getCaptureId(), finalResult));

        return Optional.of(record);
    }

    /**
     * 设备端侧取药结论归一为二值：上报为空 / 非法值 → {@code UNTAKEN}（容错兜底，见 07 §1）。
     */
    static String normalizeTakenResult(String reported) {
        if (reported == null || reported.isBlank()) {
            return "UNTAKEN";
        }
        return "TAKEN".equals(reported.trim().toUpperCase(Locale.ROOT)) ? "TAKEN" : "UNTAKEN";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String s, String fallback) {
        return isBlank(s) ? fallback : s;
    }
}

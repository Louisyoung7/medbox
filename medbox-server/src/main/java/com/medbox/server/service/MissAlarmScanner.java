package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.AlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 漏服判定与 MISS 告警（feat/miss-alarm，07 清单 P0 第 10 步）。
 *
 * <p><b>触发</b>：仅定时扫描（文档 01 §5.1 口径），每 {@code medbox.alarm.miss.scan-interval-ms}
 * 毫秒跑一次；不消费 {@code DispenseRecordedEvent} 做事件驱动即时判定（该事件仅预留）。
 *
 * <p><b>判定流程（单条计划）</b>：
 * <ol>
 *   <li>取窗口 {@code [now - lookback, now - tolerance]} 内、按设备本地墙钟展开的候选槽（{@link PlanSlotResolver}）；</li>
 *   <li>一次拉取该计划在窗口内的全部 {@code med_record}，按槽瞬时分组，得到每个槽已有的 {@code plan_item_id}；</li>
 *   <li>任一 item 不在集合内即判 MISS（CAMERA / MANUAL 来源都算），生成 <b>1 条</b>告警，{@code message} 列出全部缺失药；</li>
 *   <li>按 {@code dedup_key = MISS:{planId}:{slotUTC}} 落库，唯一索引冲突即跳过（不重复告警、不重复推 WS）。</li>
 * </ol>
 *
 * <p><b>跳过规则（用户确认）</b>：{@code sync_state = SYNC_FAILED} 的计划不判（设备没收到计划不会提醒）；
 * {@code status != ENABLED} 不判（由 {@code MedPlanMapper.selectDueForMissScan} 过滤）；
 * 主控设备 {@code online_state != ONLINE} 跳过（避免"上报不了 = 漏服"误判，由 {@code skipWhenDeviceOffline} 开关）。
 *
 * <p><b>推送</b>：走 {@link WsPushService} 这一个出口（同 {@code DeviceStatusEventBridge} 套路）——用
 * {@link ObjectProvider} 注入，{@code medbox.ws.enabled=false} 时取不到实例则静默跳过；推送失败只记日志，
 * 不阻断扫描。全程兜异常，单条计划出错不影响其余计划。
 *
 * <p>规则表细节沉淀在文档 06 §2.9。
 */
@Component
public class MissAlarmScanner {

    private static final Logger log = LoggerFactory.getLogger(MissAlarmScanner.class);

    static final String TYPE_MISS = "MISS";
    static final String LEVEL_WARN = "WARN";

    private final MedPlanMapper medPlanMapper;
    private final MedPlanItemMapper medPlanItemMapper;
    private final MedRecordMapper medRecordMapper;
    private final AlarmMapper alarmMapper;
    private final DeviceMapper deviceMapper;
    private final MedicineMapper medicineMapper;
    private final MissToleranceResolver toleranceResolver;
    private final DeviceTimezoneResolver timezoneResolver;
    private final PlanSlotResolver planSlotResolver;
    private final AlarmProperties alarmProperties;
    private final ObjectProvider<WsPushService> wsPushService;

    public MissAlarmScanner(MedPlanMapper medPlanMapper, MedPlanItemMapper medPlanItemMapper,
                            MedRecordMapper medRecordMapper, AlarmMapper alarmMapper,
                            DeviceMapper deviceMapper, MedicineMapper medicineMapper,
                            MissToleranceResolver toleranceResolver, DeviceTimezoneResolver timezoneResolver,
                            PlanSlotResolver planSlotResolver, AlarmProperties alarmProperties,
                            ObjectProvider<WsPushService> wsPushService) {
        this.medPlanMapper = medPlanMapper;
        this.medPlanItemMapper = medPlanItemMapper;
        this.medRecordMapper = medRecordMapper;
        this.alarmMapper = alarmMapper;
        this.deviceMapper = deviceMapper;
        this.medicineMapper = medicineMapper;
        this.toleranceResolver = toleranceResolver;
        this.timezoneResolver = timezoneResolver;
        this.planSlotResolver = planSlotResolver;
        this.alarmProperties = alarmProperties;
        this.wsPushService = wsPushService;
    }

    /** 定时入口：每个间隔扫一次，用系统时钟。 */
    @Scheduled(fixedDelayString = "${medbox.alarm.miss.scan-interval-ms:60000}")
    public void scheduledScan() {
        scan(Clock.systemUTC());
    }

    /**
     * 核心扫描（可注入时钟便于测试）。
     *
     * @param clock 时钟（测试用固定时钟）
     */
    public void scan(Clock clock) {
        if (!alarmProperties.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        Instant lookback = now.minus(alarmProperties.getLookbackMinutes(), ChronoUnit.MINUTES);
        List<MedPlan> plans = medPlanMapper.selectDueForMissScan();
        if (plans.isEmpty()) {
            return;
        }
        log.debug("漏服扫描开始：计划 {} 个，窗口 [{} ~ {}]", plans.size(), lookback, now);
        for (MedPlan plan : plans) {
            try {
                scanPlan(plan, now, lookback);
            } catch (RuntimeException ex) {
                // 单条计划出错不影响其余计划（旁路任务，失败只记日志）
                log.warn("漏服扫描单条计划失败 planId={}：{}", plan.getPlanId(), ex.getMessage());
            }
        }
    }

    private void scanPlan(MedPlan plan, Instant now, Instant lookback) {
        String elderId = plan.getElderId();
        ZoneId zone = timezoneResolver.resolve(elderId);

        // 容忍窗口：槽必须早于 now - tolerance 才算"过了容忍期"
        int toleranceMin = toleranceResolver.resolveMinutes(plan, elderId);
        Instant windowEnd = now.minus(toleranceMin, ChronoUnit.MINUTES);
        Instant windowStart = lookback;
        if (windowStart.isAfter(windowEnd)) {
            return; // 容忍大于回看，无候选槽
        }

        if (alarmProperties.isSkipWhenDeviceOffline()) {
            Device main = deviceMapper.selectMainByElderId(elderId);
            if (main == null || main.getOnlineState() != OnlineState.ONLINE) {
                log.debug("漏服扫描跳过：主控离线或未登记 planId={} online={}",
                        plan.getPlanId(), main == null ? "无设备" : main.getOnlineState());
                return;
            }
        }

        // 候选槽
        List<OffsetDateTime> slots = planSlotResolver.expandSlots(windowStart, windowEnd, zone, plan.getTimes());
        if (slots.isEmpty()) {
            return;
        }

        // 窗口内全部记录，按槽瞬时分组
        List<MedRecord> records = medRecordMapper.selectByPlanAndTimeRange(
                plan.getPlanId(), OffsetDateTime.ofInstant(windowStart, zone), OffsetDateTime.ofInstant(windowEnd, zone));
        Map<Instant, Set<String>> presentItemsBySlot = new LinkedHashMap<>();
        for (MedRecord r : records) {
            if (r.getPlanTime() == null || r.getPlanItemId() == null) {
                continue;
            }
            presentItemsBySlot.computeIfAbsent(r.getPlanTime().toInstant(), k -> new HashSet<>())
                    .add(r.getPlanItemId());
        }

        // 该计划全部 item（不重复）
        List<MedPlanItem> items = medPlanItemMapper.selectList(
                new LambdaQueryWrapper<MedPlanItem>().eq(MedPlanItem::getPlanId, plan.getPlanId()));
        if (items.isEmpty()) {
            return;
        }

        for (OffsetDateTime slot : slots) {
            Set<String> present = presentItemsBySlot.getOrDefault(slot.toInstant(), Set.of());
            List<MedPlanItem> missing = items.stream()
                    .filter(it -> !present.contains(it.getItemId()))
                    .collect(Collectors.toList());
            if (missing.isEmpty()) {
                continue;
            }
            raiseMissAlarm(plan, slot, items, missing);
        }
    }

    private void raiseMissAlarm(MedPlan plan, OffsetDateTime slot, List<MedPlanItem> allItems, List<MedPlanItem> missing) {
        String elderId = plan.getElderId();
        List<String> missingMedicineIds = missing.stream()
                .map(MedPlanItem::getMedicineId).filter(StringUtils::isNotBlank).distinct().collect(Collectors.toList());

        String message = buildMessage(slot, missingMedicineIds);
        String dedupKey = TYPE_MISS + ":" + plan.getPlanId() + ":" + slot.toInstant().toString();

        Alarm alarm = new Alarm();
        alarm.setAlarmId(alarmMapper.nextAlarmId());
        alarm.setElderId(elderId);
        alarm.setPlanId(plan.getPlanId());
        alarm.setDeviceId(deviceIdOf(elderId));
        alarm.setMedicineId(missingMedicineIds.isEmpty() ? null : missingMedicineIds.get(0));
        alarm.setType(TYPE_MISS);
        alarm.setLevel(LEVEL_WARN);
        alarm.setMessage(message);
        alarm.setReadState(false);
        alarm.setHandleState("OPEN");
        alarm.setDedupKey(dedupKey);
        alarm.setCreatedAt(OffsetDateTime.now());

        try {
            alarmMapper.insert(alarm);
        } catch (DuplicateKeyException ex) {
            // 该槽已经告警过（重启/重扫同窗口），跳过，不重复告警、不重复推
            log.debug("漏服告警去重命中：跳过 planId={} slot={}", plan.getPlanId(), slot.toInstant());
            return;
        }

        pushWs(alarm, elderId, missingMedicineIds);
    }

    private String buildMessage(OffsetDateTime slot, List<String> missingMedicineIds) {
        String timeLabel = slot.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        String drugs = missingMedicineIds.stream()
                .map(this::medicineName)
                .collect(Collectors.joining("、"));
        return timeLabel + " 计划服药未按时服用：缺 " + drugs;
    }

    private String medicineName(String medicineId) {
        if (medicineId == null) {
            return "未知药品";
        }
        Medicine medicine = medicineMapper.selectById(medicineId);
        return (medicine != null && StringUtils.isNotBlank(medicine.getName()))
                ? medicine.getName() : medicineId;
    }

    private String deviceIdOf(String elderId) {
        Device main = deviceMapper.selectMainByElderId(elderId);
        return main == null ? null : main.getDeviceId();
    }

    private void pushWs(Alarm alarm, String elderId, List<String> medicineIds) {
        WsPushService pusher = wsPushService.getIfAvailable();
        if (pusher == null) {
            log.debug("WS 未启用，跳过 MISS 告警推送 alarmId={}", alarm.getAlarmId());
            return;
        }
        try {
            pusher.publish(WsTopic.elder(elderId), WsFrameType.ALARM,
                    new com.medbox.server.ws.payload.AlarmPayload(
                            alarm.getAlarmId(), alarm.getType(), alarm.getLevel(),
                            alarm.getMessage(), elderId, new ArrayList<>(medicineIds)));
        } catch (RuntimeException ex) {
            log.warn("MISS 告警 WS 推送失败 alarmId={}：{}", alarm.getAlarmId(), ex.getMessage());
        }
    }
}

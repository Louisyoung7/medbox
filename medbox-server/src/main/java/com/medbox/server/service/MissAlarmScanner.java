package com.medbox.server.service;

import com.medbox.server.config.AlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.AlarmPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 漏服判定与 MISS 告警（见 07 §2）。
 *
 * <p><b>触发</b>：仅定时扫描（每 {@code medbox.alarm.miss.scan-interval-ms} 一次），
 * 窗口 {@code [now - lookback, now - tolerance]}；不消费服药记录事件做即时判定。
 *
 * <p><b>判定流程（单条计划）</b>：按 {@code slots} 展开带格号的候选槽 → 一次拉取窗口内全部
 * {@code med_record}，按 {@code (cell_no, plan_time)} 瞬时分组 → 某槽无任何记录即判 MISS，
 * 生成 1 条告警（message 只提格号与时间），按
 * {@code dedup_key = MISS:{planId}:{cellNo}:{slotUTC}} 去重。该格无排程不参与判定。
 *
 * <p><b>跳过规则</b>：{@code SYNC_FAILED} / 非 ENABLED 计划不判；主控离线时按开关跳过。
 * 单条计划出错只记日志，不影响其余。
 */
@Component
public class MissAlarmScanner {

    private static final Logger log = LoggerFactory.getLogger(MissAlarmScanner.class);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    static final String TYPE_MISS = "MISS";
    static final String LEVEL_WARN = "WARN";

    private final MedPlanMapper medPlanMapper;
    private final MedRecordMapper medRecordMapper;
    private final AlarmMapper alarmMapper;
    private final DeviceMapper deviceMapper;
    private final MissToleranceResolver toleranceResolver;
    private final DeviceTimezoneResolver timezoneResolver;
    private final PlanSlotResolver planSlotResolver;
    private final AlarmProperties alarmProperties;
    private final ObjectProvider<WsPushService> wsPushService;

    public MissAlarmScanner(MedPlanMapper medPlanMapper, MedRecordMapper medRecordMapper,
                            AlarmMapper alarmMapper, DeviceMapper deviceMapper,
                            MissToleranceResolver toleranceResolver, DeviceTimezoneResolver timezoneResolver,
                            PlanSlotResolver planSlotResolver, AlarmProperties alarmProperties,
                            ObjectProvider<WsPushService> wsPushService) {
        this.medPlanMapper = medPlanMapper;
        this.medRecordMapper = medRecordMapper;
        this.alarmMapper = alarmMapper;
        this.deviceMapper = deviceMapper;
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

    /** 核心扫描（可注入时钟便于测试）。 */
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
                log.warn("漏服扫描单条计划失败 planId={}：{}", plan.getPlanId(), ex.getMessage());
            }
        }
    }

    private void scanPlan(MedPlan plan, Instant now, Instant lookback) {
        String elderId = plan.getElderId();
        ZoneId zone = timezoneResolver.resolve(elderId);

        int toleranceMin = toleranceResolver.resolveMinutes(plan, elderId);
        Instant windowEnd = now.minus(toleranceMin, ChronoUnit.MINUTES);
        if (lookback.isAfter(windowEnd)) {
            return;
        }

        if (alarmProperties.isSkipWhenDeviceOffline()) {
            Device main = deviceMapper.selectMainByElderId(elderId);
            if (main == null || main.getOnlineState() != OnlineState.ONLINE) {
                log.debug("漏服扫描跳过：主控离线或未登记 planId={} online={}",
                        plan.getPlanId(), main == null ? "无设备" : main.getOnlineState());
                return;
            }
        }

        List<PlanSlotResolver.SlotInstant> slots =
                planSlotResolver.expandSlots(lookback, windowEnd, zone, plan.getSlots());
        if (slots.isEmpty()) {
            return;
        }

        List<MedRecord> records = medRecordMapper.selectByPlanAndTimeRange(
                plan.getPlanId(), OffsetDateTime.ofInstant(lookback, zone), OffsetDateTime.ofInstant(windowEnd, zone));
        Set<String> presentSlots = new HashSet<>();
        for (MedRecord r : records) {
            if (r.getPlanTime() == null || !PlanSlotResolver.isValidCellNo(r.getCellNo())) {
                continue;
            }
            presentSlots.add(slotKey(r.getCellNo(), r.getPlanTime().toInstant()));
        }

        for (PlanSlotResolver.SlotInstant slot : slots) {
            if (presentSlots.contains(slotKey(slot.cellNo(), slot.slotTime().toInstant()))) {
                continue;
            }
            raiseMissAlarm(plan, slot);
        }
    }

    private static String slotKey(Integer cellNo, Instant planTime) {
        return cellNo + ":" + planTime;
    }

    private void raiseMissAlarm(MedPlan plan, PlanSlotResolver.SlotInstant slot) {
        String elderId = plan.getElderId();
        Integer cellNo = slot.cellNo();
        Instant slotInstant = slot.slotTime().toInstant();
        String dedupKey = TYPE_MISS + ":" + plan.getPlanId() + ":" + cellNo + ":" + slotInstant;

        Alarm alarm = new Alarm();
        alarm.setAlarmId(alarmMapper.nextAlarmId());
        alarm.setElderId(elderId);
        alarm.setPlanId(plan.getPlanId());
        alarm.setDeviceId(deviceIdOf(elderId));
        alarm.setCellNo(cellNo);
        alarm.setType(TYPE_MISS);
        alarm.setLevel(LEVEL_WARN);
        alarm.setMessage(buildMessage(slot));
        alarm.setReadState(false);
        alarm.setHandleState("OPEN");
        alarm.setDedupKey(dedupKey);
        alarm.setCreatedAt(OffsetDateTime.now());

        try {
            alarmMapper.insert(alarm);
        } catch (DuplicateKeyException ex) {
            log.debug("漏服告警去重命中：跳过 planId={} cellNo={} slot={}",
                    plan.getPlanId(), cellNo, slotInstant);
            return;
        }
        pushWs(alarm, elderId);
    }

    /** message 只提格号与时间，不提药品名（07 §2.5）。 */
    private String buildMessage(PlanSlotResolver.SlotInstant slot) {
        String timeLabel = slot.slotTime().toLocalTime().format(HH_MM);
        String period = switch (slot.cellNo()) {
            case 1 -> "早间";
            case 2 -> "午间";
            default -> "晚间";
        };
        return timeLabel + " " + slot.cellNo() + " 号格（" + period + "）未按时取药";
    }

    private String deviceIdOf(String elderId) {
        Device main = deviceMapper.selectMainByElderId(elderId);
        return main == null ? null : main.getDeviceId();
    }

    private void pushWs(Alarm alarm, String elderId) {
        WsPushService pusher = wsPushService.getIfAvailable();
        if (pusher == null) {
            log.debug("WS 未启用，跳过 MISS 告警推送 alarmId={}", alarm.getAlarmId());
            return;
        }
        try {
            pusher.publish(WsTopic.elder(elderId), WsFrameType.ALARM,
                    new AlarmPayload(alarm.getAlarmId(), alarm.getType(), alarm.getLevel(),
                            alarm.getMessage(), elderId));
        } catch (RuntimeException ex) {
            log.warn("MISS 告警 WS 推送失败 alarmId={}：{}", alarm.getAlarmId(), ex.getMessage());
        }
    }
}

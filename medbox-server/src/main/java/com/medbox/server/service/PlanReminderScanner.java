package com.medbox.server.service;

import com.medbox.server.config.PlanReminderProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.PlanReminder;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.PlanReminderMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.ReminderPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 服药提醒推送（见 07 §4）。
 *
 * <p><b>触发</b>：仅定时扫描，窗口 {@code [now, now + leadTimeMinutes]}；按设备本地时区把
 * {@code slots} 展开为带格号的未来槽，经 {@link WsPushService} 推 {@code REMINDER} 帧
 * {@code {planId, planTime, cellNo}}（不含药品信息）。设备本地声光提醒为主，云端 WS 为兜底。
 *
 * <p><b>两道拦截</b>：① 该槽已有 {@code med_record}（按 {@code cell_no + plan_time}）→ 跳过；
 * ② 去重表 {@code plan_reminder} 的 {@code REMINDER:{planId}:{cellNo}:{slotUTC}} 冲突 → 跳过。
 */
@Component
public class PlanReminderScanner {

    private static final Logger log = LoggerFactory.getLogger(PlanReminderScanner.class);
    private static final String DEDUP_PREFIX = "REMINDER";

    private final MedPlanMapper medPlanMapper;
    private final MedRecordMapper medRecordMapper;
    private final PlanReminderMapper planReminderMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceTimezoneResolver timezoneResolver;
    private final PlanSlotResolver planSlotResolver;
    private final PlanReminderProperties properties;
    private final ObjectProvider<WsPushService> wsPushService;

    public PlanReminderScanner(MedPlanMapper medPlanMapper, MedRecordMapper medRecordMapper,
                               PlanReminderMapper planReminderMapper, DeviceMapper deviceMapper,
                               DeviceTimezoneResolver timezoneResolver, PlanSlotResolver planSlotResolver,
                               PlanReminderProperties properties, ObjectProvider<WsPushService> wsPushService) {
        this.medPlanMapper = medPlanMapper;
        this.medRecordMapper = medRecordMapper;
        this.planReminderMapper = planReminderMapper;
        this.deviceMapper = deviceMapper;
        this.timezoneResolver = timezoneResolver;
        this.planSlotResolver = planSlotResolver;
        this.properties = properties;
        this.wsPushService = wsPushService;
    }

    /** 定时入口：每个间隔扫一次，用系统时钟。 */
    @Scheduled(fixedDelayString = "${medbox.plan-reminder.scan-interval-ms:60000}")
    public ScanSummary scheduledScan() {
        return scan(Clock.systemUTC());
    }

    /** 核心扫描（可注入时钟便于测试 / 手动触发）。 */
    public ScanSummary scan(Clock clock) {
        if (!properties.isEnabled()) {
            log.debug("提醒扫描跳过：plan-reminder.enabled=false");
            return ScanSummary.empty();
        }
        Instant now = clock.instant();
        Instant windowEnd = now.plus(properties.getLeadTimeMinutes(), ChronoUnit.MINUTES);
        List<MedPlan> plans = medPlanMapper.selectDueForReminder();
        if (plans.isEmpty()) {
            return ScanSummary.empty();
        }
        Counters c = new Counters();
        log.debug("提醒扫描开始：计划 {} 个，窗口 [{} ~ {}]", plans.size(), now, windowEnd);
        for (MedPlan plan : plans) {
            try {
                scanPlan(plan, now, windowEnd, c);
            } catch (RuntimeException ex) {
                log.warn("提醒扫描单条计划失败 planId={}：{}", plan.getPlanId(), ex.getMessage());
            }
        }
        ScanSummary summary = c.toSummary(plans.size());
        log.info("提醒扫描完成：{}", summary);
        return summary;
    }

    private void scanPlan(MedPlan plan, Instant now, Instant windowEnd, Counters c) {
        String elderId = plan.getElderId();
        ZoneId zone = timezoneResolver.resolve(elderId);

        if (properties.isSkipWhenDeviceOffline()) {
            Device main = deviceMapper.selectMainByElderId(elderId);
            if (main == null || main.getOnlineState() != OnlineState.ONLINE) {
                log.debug("提醒扫描跳过：主控离线或未登记 planId={} online={}",
                        plan.getPlanId(), main == null ? "无设备" : main.getOnlineState());
                c.skippedOffline++;
                return;
            }
        }

        List<PlanSlotResolver.SlotInstant> slots =
                planSlotResolver.expandSlots(now, windowEnd, zone, plan.getSlots());
        if (slots.isEmpty()) {
            c.skippedNoFutureSlot++;
            return;
        }
        for (PlanSlotResolver.SlotInstant slot : slots) {
            remindSlot(plan, slot, c);
        }
    }

    private void remindSlot(MedPlan plan, PlanSlotResolver.SlotInstant slot, Counters c) {
        String elderId = plan.getElderId();
        Integer cellNo = slot.cellNo();
        Instant slotInstant = slot.slotTime().toInstant();
        OffsetDateTime slotUtc = OffsetDateTime.ofInstant(slotInstant, ZoneOffset.UTC);

        // ① 已服跳过：该格该槽已有服药记录（CAMERA / MANUAL 都算）
        int taken = medRecordMapper.countTaken(plan.getPlanId(), cellNo, slotUtc);
        if (taken > 0) {
            log.debug("提醒跳过：该格该槽已服药 planId={} cellNo={} slot={}",
                    plan.getPlanId(), cellNo, slotInstant);
            c.skippedTaken++;
            return;
        }

        // ② 去重：插入冲突即表示已提醒过
        String dedupKey = DEDUP_PREFIX + ":" + plan.getPlanId() + ":" + cellNo + ":" + slotInstant;
        PlanReminder rec = new PlanReminder();
        rec.setDedupKey(dedupKey);
        rec.setPlanId(plan.getPlanId());
        rec.setElderId(elderId);
        rec.setCellNo(cellNo);
        rec.setSlotUtc(slotUtc);
        rec.setCreatedAt(OffsetDateTime.now());
        try {
            planReminderMapper.insert(rec);
        } catch (DuplicateKeyException ex) {
            log.debug("提醒去重命中：跳过 planId={} cellNo={} slot={}", plan.getPlanId(), cellNo, slotInstant);
            c.skippedDedup++;
            return;
        }

        if (pushWs(plan, cellNo, slotInstant)) {
            c.pushed++;
        }
    }

    private boolean pushWs(MedPlan plan, Integer cellNo, Instant slotInstant) {
        WsPushService pusher = wsPushService.getIfAvailable();
        if (pusher == null) {
            log.debug("WS 未启用，跳过 REMINDER 推送 planId={}", plan.getPlanId());
            return false;
        }
        try {
            pusher.publish(WsTopic.elder(plan.getElderId()), WsFrameType.REMINDER,
                    new ReminderPayload(plan.getPlanId(), slotInstant.toString(), cellNo));
            return true;
        } catch (RuntimeException ex) {
            log.warn("REMINDER WS 推送失败 planId={}：{}", plan.getPlanId(), ex.getMessage());
            return false;
        }
    }

    /** 本次扫描的各类计数。 */
    private static final class Counters {
        private long skippedOffline;
        private long skippedNoFutureSlot;
        private long skippedTaken;
        private long skippedDedup;
        private long pushed;

        ScanSummary toSummary(long scannedPlans) {
            return new ScanSummary(scannedPlans, pushed, skippedTaken, skippedDedup,
                    skippedOffline, skippedNoFutureSlot);
        }
    }

    /** 一次扫描的汇总（调试触发端点返回 / 日志排障用）。 */
    public record ScanSummary(long scannedPlans, long pushed, long skippedTaken,
                              long skippedDedup, long skippedOffline, long skippedNoFutureSlot) {
        public static ScanSummary empty() {
            return new ScanSummary(0, 0, 0, 0, 0, 0);
        }

        @Override
        public String toString() {
            return "扫描计划=" + scannedPlans + " 推送=" + pushed
                    + " 跳过(已服)=" + skippedTaken + " 跳过(去重)=" + skippedDedup
                    + " 跳过(离线)=" + skippedOffline + " 跳过(无未来槽)=" + skippedNoFutureSlot;
        }
    }
}

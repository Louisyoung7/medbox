package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.medbox.server.config.PlanReminderProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.PlanReminder;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedicineMapper;
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
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 服药提醒推送（feat/plan-reminder，07 清单）。
 *
 * <p><b>触发</b>：仅定时扫描，每 {@code medbox.plan-reminder.scan-interval-ms} 毫秒跑一次；
 * 在计划服药时间<b>临近</b>时（{@code [now, now + leadTimeMinutes]} 窗口内、按设备本地时区展开的未来 slot）
 * 经 {@link WsPushService} 向 {@code elder:{elderId}} 推 {@code REMINDER} 帧。
 *
 * <p><b>两台语义</b>：设备本地声光提醒为主（设备按 plan-sync 下发的计划自己响），云端 WS 提醒是
 * <b>监护端 / 老人 App 兜底</b>——故默认<b>不要求设备在线</b>（{@code skip-when-device-offline} 默认 false）；
 * 已过的槽归 {@code feat/miss-alarm}，本分支只管"未来"槽。
 *
 * <p><b>两道拦截</b>：① 该 slot 已有 {@code med_record}（已服）→ 跳过；② 去重表
 * {@code plan_reminder} 已有 {@code REMINDER:{planId}:{slotUTC}} → 跳过（唯一索引冲突即跳过）。
 * 扫描间隔 < 提前量时靠去重保证每个 slot 只推一次。
 *
 * <p><b>推送</b>：走 {@link WsPushService} 唯一出口（同 miss-alarm 套路），{@code ws.enabled=false}
 * 或推送异常静默跳过、不阻断扫描；全程兜异常，单条计划出错不影响其余。
 */
@Component
public class PlanReminderScanner {

    private static final Logger log = LoggerFactory.getLogger(PlanReminderScanner.class);
    private static final String DEDUP_PREFIX = "REMINDER";

    private final MedPlanMapper medPlanMapper;
    private final MedPlanItemMapper medPlanItemMapper;
    private final MedRecordMapper medRecordMapper;
    private final PlanReminderMapper planReminderMapper;
    private final DeviceMapper deviceMapper;
    private final MedicineMapper medicineMapper;
    private final DeviceTimezoneResolver timezoneResolver;
    private final PlanSlotResolver planSlotResolver;
    private final PlanReminderProperties properties;
    private final ObjectProvider<WsPushService> wsPushService;

    public PlanReminderScanner(MedPlanMapper medPlanMapper, MedPlanItemMapper medPlanItemMapper,
                               MedRecordMapper medRecordMapper, PlanReminderMapper planReminderMapper,
                               DeviceMapper deviceMapper, MedicineMapper medicineMapper,
                               DeviceTimezoneResolver timezoneResolver, PlanSlotResolver planSlotResolver,
                               PlanReminderProperties properties, ObjectProvider<WsPushService> wsPushService) {
        this.medPlanMapper = medPlanMapper;
        this.medPlanItemMapper = medPlanItemMapper;
        this.medRecordMapper = medRecordMapper;
        this.planReminderMapper = planReminderMapper;
        this.deviceMapper = deviceMapper;
        this.medicineMapper = medicineMapper;
        this.timezoneResolver = timezoneResolver;
        this.planSlotResolver = planSlotResolver;
        this.properties = properties;
        this.wsPushService = wsPushService;
    }

    /** 定时入口：每个间隔扫一次，用系统时钟。 */
    @Scheduled(fixedDelayString = "${medbox.plan-reminder.scan-interval-ms:60000}")
    public void scheduledScan() {
        scan(Clock.systemUTC());
    }

    /**
     * 核心扫描（可注入时钟便于测试）。
     *
     * @param clock 时钟（测试用固定时钟）
     */
    public void scan(Clock clock) {
        if (!properties.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        Instant windowEnd = now.plus(properties.getLeadTimeMinutes(), ChronoUnit.MINUTES);
        List<MedPlan> plans = medPlanMapper.selectDueForReminder();
        if (plans.isEmpty()) {
            return;
        }
        log.debug("提醒扫描开始：计划 {} 个，窗口 [{} ~ {}]", plans.size(), now, windowEnd);
        for (MedPlan plan : plans) {
            try {
                scanPlan(plan, now, windowEnd);
            } catch (RuntimeException ex) {
                // 单条计划出错不影响其余计划（旁路任务，失败只记日志）
                log.warn("提醒扫描单条计划失败 planId={}：{}", plan.getPlanId(), ex.getMessage());
            }
        }
    }

    private void scanPlan(MedPlan plan, Instant now, Instant windowEnd) {
        String elderId = plan.getElderId();
        ZoneId zone = timezoneResolver.resolve(elderId);

        if (properties.isSkipWhenDeviceOffline()) {
            Device main = deviceMapper.selectMainByElderId(elderId);
            if (main == null || main.getOnlineState() != OnlineState.ONLINE) {
                log.debug("提醒扫描跳过：主控离线或未登记 planId={} online={}",
                        plan.getPlanId(), main == null ? "无设备" : main.getOnlineState());
                return;
            }
        }

        // 仅对未来 slot 提醒（含边界）：now <= slot <= now + leadTime
        List<OffsetDateTime> slots = planSlotResolver.expandSlots(now, windowEnd, zone, plan.getTimes());
        if (slots.isEmpty()) {
            return;
        }
        for (OffsetDateTime slot : slots) {
            remindSlot(plan, slot);
        }
    }

    private void remindSlot(MedPlan plan, OffsetDateTime slot) {
        String elderId = plan.getElderId();
        Instant slotInstant = slot.toInstant();
        OffsetDateTime slotUtc = OffsetDateTime.ofInstant(slotInstant, ZoneOffset.UTC);

        // ① 已服跳过：该 slot 已有服药记录则不再提醒
        int taken = medRecordMapper.countTaken(plan.getPlanId(), slotUtc);
        if (taken > 0) {
            log.debug("提醒跳过：该槽已服药 planId={} slot={}", plan.getPlanId(), slotInstant);
            return;
        }

        // ② 去重：插入冲突即表示已提醒过
        String dedupKey = DEDUP_PREFIX + ":" + plan.getPlanId() + ":" + slotInstant.toString();
        PlanReminder rec = new PlanReminder();
        rec.setDedupKey(dedupKey);
        rec.setPlanId(plan.getPlanId());
        rec.setElderId(elderId);
        rec.setSlotUtc(slotUtc);
        rec.setCreatedAt(OffsetDateTime.now());
        try {
            planReminderMapper.insert(rec);
        } catch (DuplicateKeyException ex) {
            log.debug("提醒去重命中：跳过 planId={} slot={}", plan.getPlanId(), slotInstant);
            return;
        }

        pushWs(plan, slot);
    }

    private void pushWs(MedPlan plan, OffsetDateTime slot) {
        WsPushService pusher = wsPushService.getIfAvailable();
        if (pusher == null) {
            log.debug("WS 未启用，跳过 REMINDER 推送 planId={}", plan.getPlanId());
            return;
        }
        try {
            List<MedPlanItem> items = medPlanItemMapper.selectList(
                    new LambdaQueryWrapper<MedPlanItem>().eq(MedPlanItem::getPlanId, plan.getPlanId()));
            List<ReminderPayload.ReminderMedicine> medicines = items.stream()
                    .map(it -> {
                        Medicine m = medicineMapper.selectById(it.getMedicineId());
                        String name = (m != null && StringUtils.isNotBlank(m.getName()))
                                ? m.getName() : it.getMedicineId();
                        return new ReminderPayload.ReminderMedicine(
                                it.getItemId(), it.getMedicineId(), name, it.getDose(), it.getUnit());
                    })
                    .collect(Collectors.toList());
            ReminderPayload payload = new ReminderPayload(
                    plan.getPlanId(), slot.toInstant().toString(), medicines);
            pusher.publish(WsTopic.elder(plan.getElderId()), WsFrameType.REMINDER, payload);
        } catch (RuntimeException ex) {
            log.warn("REMINDER WS 推送失败 planId={}：{}", plan.getPlanId(), ex.getMessage());
        }
    }
}

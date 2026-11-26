package com.medbox.server.mqtt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.service.PlanSyncEvent;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.PlanSyncPayload;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 计划下发落地（见文档 03 第 3 章 / 02d）。
 *
 * <p><b>解耦点</b>：不反向依赖 {@code PlanService}，只听它提交后发的 {@link PlanSyncEvent}，
 * 事务提交后再触达 MQTT —— 计划落库与设备下发解耦，下行失败绝不回滚计划落库事务。
 * {@code medbox.mqtt.enabled=false} 时 {@link DownlinkPublisher} 为空，监听器直接 no-op，
 * {@code sync_state} 保持 {@code PENDING}。
 *
 * <p><b>载荷</b>：UPSERT 直接下发计划的 {@code slots}（格号 + 开格时间），不再回查药品明细；
 * DELETE 只带 planId。格号 1 / 2 / 3 由固件固定对应早 / 中 / 晚。
 *
 * <p><b>多回执并发</b>：手动同步允许连点、不去重，同一计划可能同时挂多条待回执下行。
 * {@code latestByPlan} 记录每个计划最新一次下发的 msgId；只有最新那条回执改状态并推
 * {@code PLAN_SYNC}，迟到的旧回执仅出账本、不改状态（防止"旧 FAILED 覆盖新 OK"）。
 */
@Component
public class MqttPlanScheduleSync implements PendingAckRegistry.DownlinkCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(MqttPlanScheduleSync.class);

    private static final String SYNCED_TO_DEVICE = "SYNCED_TO_DEVICE";
    private static final String SYNC_FAILED = "SYNC_FAILED";

    private final MedPlanMapper medPlanMapper;
    private final DeviceMapper deviceMapper;
    private final ObjectProvider<WsPushService> wsPushService;

    /** 下行发布器；MQTT 禁用时由 Spring 注入 null。 */
    @Autowired(required = false)
    private DownlinkPublisher downlinkPublisher;

    /** 待回执账本；MQTT 禁用时同样为 null。 */
    @Autowired(required = false)
    private PendingAckRegistry registry;

    /** msgId → (planId, elderId)，用于把 {@code up/ack} 关联回计划与推送主题。 */
    private final ConcurrentMap<String, PendingPlanSync> pendingSync = new ConcurrentHashMap<>();

    /** planId → 最新一次下发的 msgId；仅最新回执允许改状态（旧回执迟到时忽略）。 */
    private final ConcurrentMap<String, String> latestByPlan = new ConcurrentHashMap<>();

    public MqttPlanScheduleSync(MedPlanMapper medPlanMapper, DeviceMapper deviceMapper,
                                ObjectProvider<WsPushService> wsPushService) {
        this.medPlanMapper = medPlanMapper;
        this.deviceMapper = deviceMapper;
        this.wsPushService = wsPushService;
    }

    @PostConstruct
    public void init() {
        if (registry != null) {
            registry.addCompletionListener(this);
        }
    }

    /** 计划写事务提交后触发；无 MAIN 设备则记 WARN 并保持 PENDING。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlanChanged(PlanSyncEvent event) {
        if (downlinkPublisher == null) {
            return;
        }
        Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getElderId, event.getElderId()));
        if (device == null) {
            log.warn("老人无 MAIN 设备，无法下发计划 planId={} elderId={}",
                    event.getPlanId(), event.getElderId());
            return;
        }

        ScheduleDownlinkPayload payload = buildPayload(event);
        if (payload == null) {
            return;
        }

        try {
            String msgId = downlinkPublisher.publish(device.getDeviceId(),
                    DownlinkPublisher.SUB_TOPIC_SCHEDULE, payload, true);
            pendingSync.put(msgId, new PendingPlanSync(event.getPlanId(), event.getElderId()));
            latestByPlan.put(event.getPlanId(), msgId);
            log.info("已下发计划 planId={} op={} deviceId={} msgId={}",
                    event.getPlanId(), payload.getOp(), device.getDeviceId(), msgId);
        } catch (RuntimeException ex) {
            log.warn("计划下发失败 planId={} elderId={}：{}",
                    event.getPlanId(), event.getElderId(), ex.getMessage());
        }
    }

    private ScheduleDownlinkPayload buildPayload(PlanSyncEvent event) {
        if (event.getOp() == PlanSyncEvent.Op.DELETE) {
            ScheduleDownlinkPayload delete = new ScheduleDownlinkPayload();
            delete.setPlanId(event.getPlanId());
            delete.setOp("DELETE");
            return delete;
        }
        MedPlan plan = medPlanMapper.selectOne(new LambdaQueryWrapper<MedPlan>()
                .eq(MedPlan::getPlanId, event.getPlanId()));
        if (plan == null) {
            log.warn("计划不存在，无法下发（可能已被删）planId={}", event.getPlanId());
            return null;
        }
        ScheduleDownlinkPayload payload = new ScheduleDownlinkPayload();
        payload.setPlanId(plan.getPlanId());
        payload.setOp("UPSERT");
        payload.setSlots(plan.getSlots());
        payload.setRepeat(plan.getRepeatRule() == null ? "DAILY" : plan.getRepeatRule());
        payload.setAlarm(new ScheduleDownlinkPayload.ScheduleAlarm(plan.getMissAlarmAfterMin()));
        return payload;
    }

    @Override
    public void onComplete(String msgId, String deviceId, boolean success, String reason) {
        PendingPlanSync pending = pendingSync.remove(msgId);
        if (pending == null) {
            return;
        }
        // 同一计划连点多次时，只有最新一次下发的回执算终态；旧回执迟到不改状态、不推送
        if (!msgId.equals(latestByPlan.get(pending.planId()))) {
            log.debug("忽略过期计划同步回执 planId={} msgId={} success={}",
                    pending.planId(), msgId, success);
            return;
        }
        latestByPlan.remove(pending.planId());

        String state = success ? SYNCED_TO_DEVICE : SYNC_FAILED;
        int updated = medPlanMapper.update(null, new LambdaUpdateWrapper<MedPlan>()
                .eq(MedPlan::getPlanId, pending.planId())
                .set(MedPlan::getSyncState, state));
        log.info("计划同步终态 planId={} deviceId={} result={} reason={}",
                pending.planId(), deviceId, state, reason);
        // 计划已删除时 update 为 0，无状态可更新；不推状态帧（客户端列表已无该项）
        if (updated > 0) {
            pushWs(pending.planId(), pending.elderId(), state);
        }
    }

    private void pushWs(String planId, String elderId, String state) {
        WsPushService pusher = wsPushService.getIfAvailable();
        if (pusher == null) {
            log.debug("WS 未启用，跳过 PLAN_SYNC 推送 planId={}", planId);
            return;
        }
        try {
            pusher.publish(WsTopic.elder(elderId), WsFrameType.PLAN_SYNC,
                    new PlanSyncPayload(planId, elderId, state));
        } catch (RuntimeException ex) {
            // WS 推送失败不影响状态落库
            log.warn("PLAN_SYNC 推送失败 planId={}：{}", planId, ex.getMessage());
        }
    }

    private record PendingPlanSync(String planId, String elderId) {
    }
}

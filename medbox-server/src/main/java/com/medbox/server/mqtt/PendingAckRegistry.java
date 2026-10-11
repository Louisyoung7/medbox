package com.medbox.server.mqtt;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 待回执下行消息的内存账本（**下行的可靠性保障**，见文档 03 第 1 章：5s 未回执重发，最多 3 次）。
 *
 * <p><b>为什么是内存而不是表</b>：演示规模下重启丢几条待确认的下行消息是可接受的代价，
 * 换来的是"不引入一张新表 + 不需要额外的清理任务"。真要做多实例或零丢失时，
 * 把 {@code pending} 换成一张带 {@code next_retry_at} 的表即可，调用方
 * （{@link DownlinkPublisher} / {@link AckUpMessageHandler}）不用改。
 *
 * <p><b>重发时 {@code msgId} 不变</b>：设备侧据此去重（同一条下行被处理两次会出问题）。
 *
 * <p>{@code setSender} 而不是构造注入：重发的执行者 {@link DownlinkPublisher} 依赖本类，
 * 构造注入会成环；由发布器在构造时把 {@code resend} 回调注册进来即可（用法由 {@code MqttConfig} 保证）。
 */
public class PendingAckRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PendingAckRegistry.class);

    private final MqttProperties props;
    private final DownlinkFailureListener failureListener;
    private final ConcurrentHashMap<String, PendingDownlink> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Sender sender;
    private volatile ScheduledExecutorService scheduler;

    public PendingAckRegistry(MqttProperties props, DownlinkFailureListener failureListener) {
        this.props = props;
        this.failureListener = failureListener;
    }

    /** 重发动作的回调接口（由 {@link DownlinkPublisher} 实现：把同一条下行按原 topic 再发一次）。 */
    public interface Sender {

        void resend(PendingDownlink entry);
    }

    @Override
    public void start() {
        running.set(true);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "mqtt-downlink-retry");
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(factory);
        scheduler.scheduleWithFixedDelay(
            this::rescanSafely,
            props.getRetryScanIntervalMs(),
            props.getRetryScanIntervalMs(),
            TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running.set(false);
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public void setSender(Sender sender) {
        this.sender = sender;
    }

    /** 登记一条待回执的下行消息（首次发送已完成，故 attempts 从 1 起算）。 */
    public void register(PendingDownlink entry) {
        pending.put(entry.msgId(), entry);
        log.debug("登记待回执下行 msgId={} topic={} attempts={}", entry.msgId(), entry.topic(), entry.attempts());
    }

    /**
     * 结算一条下行：收到成功回执，或收到明确的失败回执（都移出账本、不再重发）。
     *
     * @param ackFor 下行消息的 msgId
     * @param ok     是否成功
     * @param reason 失败原因（可空）
     * @return true 表示账本里确实有这条；false = 已超时失败 / 已完成 / 本进程没有它
     */
    public boolean settle(String ackFor, boolean ok, String reason) {
        PendingDownlink removed = pending.remove(ackFor);
        if (removed == null) {
            return false;
        }
        if (!ok) {
            // 设备明确说"做不了"，重发毫无意义；交给失败 hook（将来由 feat/alarm-core 生成告警）
            failureListener.onFailed(removed.deviceId(), removed.topic(), removed.msgId(), reason);
        }
        return true;
    }

    /**
     * 扫描并重发超时条目（供调度线程与测试调用）。
     *
     * @return 本轮实际重发的条数
     */
    public int rescan() {
        long now = System.currentTimeMillis();
        int resent = 0;
        for (PendingDownlink entry : pending.values()) {
            if (entry.nextRetryAtMillis() > now) {
                continue;
            }
            if (entry.attempts() >= props.getMaxAttempts()) {
                giveUp(entry, "超过最大下发次数仍无回执");
                continue;
            }
            // 先自增再发送：避免发送慢时同一条被下一轮重复拿起（虽然单线程调度，仍靠 CAS 保证）
            PendingDownlink sending = new PendingDownlink(
                entry.msgId(), entry.deviceId(), entry.topic(), entry.payloadJson(),
                entry.attempts() + 1, now + props.getAckTimeoutMs());
            if (!pending.replace(entry.msgId(), entry, sending)) {
                continue;
            }
            Sender current = sender;
            if (current == null) {
                log.warn("未设置重发回调，跳过 msgId={}", entry.msgId());
                continue;
            }
            try {
                current.resend(entry);
                resent++;
                log.debug("下行超时重发 msgId={} topic={} attempts={}", sending.msgId(), sending.topic(), sending.attempts());
            } catch (RuntimeException ex) {
                // 发送失败：回退 attempts，保留条目等下一轮
                pending.replace(sending.msgId(), sending, entry);
                log.warn("下行重发失败 msgId={}：{}", sending.msgId(), ex.getMessage());
            }
        }
        return resent;
    }

    /** 当前待回执条目（测试与运维观测用）。 */
    public Collection<PendingDownlink> snapshot() {
        return Map.copyOf(pending).values();
    }

    private void rescanSafely() {
        try {
            rescan();
        } catch (RuntimeException ex) {
            // 调度线程绝不能因为一次异常就停摆（ScheduledExecutorService 会静默终止后续调度）
            log.error("下行重发扫描异常", ex);
        }
    }

    private void giveUp(PendingDownlink entry, String reason) {
        pending.remove(entry.msgId());
        log.warn("下行下发失败 msgId={} topic={} attempts={}：{}", entry.msgId(), entry.topic(), entry.attempts(), reason);
        failureListener.onFailed(entry.deviceId(), entry.topic(), entry.msgId(), reason);
    }
}

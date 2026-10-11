package com.medbox.server.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 下行待回执账本：5s 超时重发、最多 3 次（这里用短超时模拟，CI 可跑）。
 *
 * <p>覆盖的是文档 03 第 1 章那条最容易写错的规则 —— "3 次"到底是<b>总共 3 次</b>还是"重发 3 次"：
 * 本实现取"含首发共 3 次"（1 次首发 + 2 次重发），与 {@code medbox.mqtt.max-attempts} 的语义一致。
 */
class PendingAckRegistryTest {

    private static final long TIMEOUT_MS = 120L;

    private RecordingFailureListener failureListener;
    private PendingAckRegistry registry;
    private AtomicInteger resendCount;
    private List<PendingDownlink> resent;

    @BeforeEach
    void setUp() {
        failureListener = new RecordingFailureListener();
        resendCount = new AtomicInteger();
        resent = new ArrayList<>();
        MqttProperties props = new MqttProperties();
        props.setAckTimeoutMs(TIMEOUT_MS);
        props.setMaxAttempts(3);
        props.setRetryScanIntervalMs(50L);
        registry = new PendingAckRegistry(props, failureListener);
        registry.setSender(entry -> {
            resendCount.incrementAndGet();
            resent.add(entry);
        });
    }

    @Test
    @DisplayName("未到重发时刻不动作")
    void noResendBeforeTimeout() {
        registry.register(new PendingDownlink("d-001", "BOXA1001", "medbox/pk/BOXA1001/down/schedule", "{}",
            1, System.currentTimeMillis() + 10_000L));

        assertThat(registry.rescan()).isZero();
        assertThat(resendCount.get()).isZero();
    }

    @Test
    @DisplayName("超时即重发，重发时 msgId 保持不变（设备侧据此去重）")
    void resendKeepsSameMsgId() throws Exception {
        long due = System.currentTimeMillis() - 1L;
        registry.register(new PendingDownlink("d-001", "BOXA1001", "medbox/pk/BOXA1001/down/schedule",
            "{\"msgId\":\"d-001\"}", 1, due));

        assertThat(registry.rescan()).isEqualTo(1);
        assertThat(resent).hasSize(1);
        assertThat(resent.get(0).msgId()).isEqualTo("d-001");
        assertThat(resent.get(0).payloadJson()).isEqualTo("{\"msgId\":\"d-001\"}");

        // 刚重发完还没到下一次超时 —— 不应在本轮清单里立刻再来一次
        assertThat(registry.rescan()).isZero();
    }

    @Test
    @DisplayName("含首发共 3 次：第 3 次仍无回执即放弃并通知失败监听")
    void givesUpAfterMaxAttempts() throws Exception {
        registry.register(new PendingDownlink("d-001", "BOXA1001", "medbox/pk/BOXA1001/down/command", "{}",
            1, System.currentTimeMillis() - 1L));

        registry.rescan();                    // → attempts 2
        Thread.sleep(TIMEOUT_MS + 40);
        registry.rescan();                    // → attempts 3
        Thread.sleep(TIMEOUT_MS + 40);
        registry.rescan();                    // 已到上限 → 放弃

        assertThat(resendCount.get()).isEqualTo(2);
        assertThat(registry.snapshot()).isEmpty();
        assertThat(failureListener.failures).containsExactly("BOXA1001|d-001");
    }

    @Test
    @DisplayName("收到 OK 回执：账本清空，后续不再重发")
    void okAckStopsRetrying() throws Exception {
        registry.register(new PendingDownlink("d-001", "BOXA1001", "medbox/pk/BOXA1001/down/schedule", "{}",
            1, System.currentTimeMillis() - 1L));

        assertThat(registry.settle("d-001", true, null)).isTrue();
        Thread.sleep(TIMEOUT_MS + 40);

        assertThat(registry.rescan()).isZero();
        assertThat(resendCount.get()).isZero();
        assertThat(failureListener.failures).isEmpty();
    }

    @Test
    @DisplayName("设备明确回 FAILED：终态，不重发但算失败")
    void failedAckIsTerminal() {
        registry.register(new PendingDownlink("d-001", "BOXA1001", "medbox/pk/BOXA1001/down/command", "{}",
            1, System.currentTimeMillis() - 1L));

        assertThat(registry.settle("d-001", false, "medicine_not_found")).isTrue();
        assertThat(registry.snapshot()).isEmpty();
        assertThat(registry.rescan()).isZero();
        assertThat(resendCount.get()).isZero();
        assertThat(failureListener.failures).containsExactly("BOXA1001|d-001");
    }

    @Test
    @DisplayName("回执找不到对应条目（已超时/已完成）返回 false，不算失败")
    void unknownAckIsIgnored() {
        assertThat(registry.settle("d-not-exist", true, null)).isFalse();
        assertThat(failureListener.failures).isEmpty();
    }

    @Test
    @DisplayName("重发动作抛异常：条目保留、attempts 回退，等下一轮再试")
    void keepsEntryWhenResendThrows() {
        registry.setSender(entry -> {
            throw new MqttTransportException("broker down");
        });
        registry.register(new PendingDownlink("d-001", "BOXA1001", "medbox/pk/BOXA1001/down/command", "{}",
            1, System.currentTimeMillis() - 1L));

        registry.rescan();

        assertThat(registry.snapshot()).hasSize(1);
        assertThat(registry.snapshot().iterator().next().attempts()).isEqualTo(1);
    }

    /** 记录失败通知的测试替身。 */
    private static class RecordingFailureListener implements DownlinkFailureListener {

        private final List<String> failures = new ArrayList<>();

        @Override
        public void onFailed(String deviceId, String topic, String msgId, String reason) {
            failures.add(deviceId + "|" + msgId);
        }
    }
}

package com.medbox.server.mqtt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.service.PlanSyncEvent;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.payload.PlanSyncPayload;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 计划下发 ACK 结算 → 状态终态 + {@code PLAN_SYNC} 推送（{@code feat/backend-plan-sync}）。
 *
 * <p>重点覆盖手动同步带来的新并发场景：同一计划连点多次时，旧回执迟到不得覆盖新状态；
 * 以及 WS 未启用 / 推送异常时的降级，均不影响状态落库。
 */
class MqttPlanScheduleSyncTest {

    private static final String PLAN_ID = "p-3301";
    private static final String ELDER_ID = "u-1001";
    private static final String DEVICE_ID = "BOXA1001";

    private MedPlanMapper medPlanMapper;
    private DeviceMapper deviceMapper;
    private WsPushService wsPushService;
    private ObjectProvider<WsPushService> wsProvider;
    private DownlinkPublisher downlinkPublisher;
    private MqttPlanScheduleSync sync;
    private final AtomicInteger msgSeq = new AtomicInteger();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        medPlanMapper = mock(MedPlanMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        wsPushService = mock(WsPushService.class);
        wsProvider = mock(ObjectProvider.class);
        when(wsProvider.getIfAvailable()).thenReturn(wsPushService);
        downlinkPublisher = mock(DownlinkPublisher.class);

        sync = new MqttPlanScheduleSync(medPlanMapper, deviceMapper, wsProvider);
        ReflectionTestUtils.setField(sync, "downlinkPublisher", downlinkPublisher);
        ReflectionTestUtils.setField(sync, "registry", null);

        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device());
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(plan("SYNCED_TO_DEVICE"));
        when(medPlanMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(downlinkPublisher.publish(eq(DEVICE_ID), eq(DownlinkPublisher.SUB_TOPIC_SCHEDULE),
                any(), eq(true))).thenAnswer(inv -> "msg-" + msgSeq.incrementAndGet());
    }

    @Test
    @DisplayName("ACK OK → sync_state 落 SYNCED_TO_DEVICE，推 PLAN_SYNC 到 elder 主题")
    void ackOk推同步状态帧() {
        sync.onPlanChanged(event(PlanSyncEvent.Op.UPSERT));
        sync.onComplete("msg-1", DEVICE_ID, true, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<MedPlan>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(medPlanMapper).update(any(), wrapper.capture());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(wsPushService).publish(eq("elder:" + ELDER_ID), eq(WsFrameType.PLAN_SYNC), payload.capture());
        PlanSyncPayload frame = (PlanSyncPayload) payload.getValue();
        assertEquals(PLAN_ID, frame.planId());
        assertEquals(ELDER_ID, frame.elderId());
        assertEquals("SYNCED_TO_DEVICE", frame.syncState());
    }

    @Test
    @DisplayName("ACK FAILED → sync_state 落 SYNC_FAILED，帧里带失败终态")
    void ackFailed推失败终态() {
        sync.onPlanChanged(event(PlanSyncEvent.Op.UPSERT));
        sync.onComplete("msg-1", DEVICE_ID, false, "设备拒绝");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(wsPushService).publish(eq("elder:" + ELDER_ID), eq(WsFrameType.PLAN_SYNC), payload.capture());
        assertEquals("SYNC_FAILED", ((PlanSyncPayload) payload.getValue()).syncState());
    }

    @Test
    @DisplayName("连点两次：旧 msgId 的迟到 FAILED 不覆盖新 OK，只推一次、更新一次")
    void 旧回执不覆盖新状态() {
        sync.onPlanChanged(event(PlanSyncEvent.Op.UPSERT));
        sync.onPlanChanged(event(PlanSyncEvent.Op.UPSERT));

        // 新回执先到 OK，旧回执后到 FAILED
        sync.onComplete("msg-2", DEVICE_ID, true, null);
        sync.onComplete("msg-1", DEVICE_ID, false, "迟到的失败");

        verify(medPlanMapper, times(1)).update(any(), any(LambdaUpdateWrapper.class));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(wsPushService, times(1)).publish(eq("elder:" + ELDER_ID), eq(WsFrameType.PLAN_SYNC), payload.capture());
        assertEquals("SYNCED_TO_DEVICE", ((PlanSyncPayload) payload.getValue()).syncState());
    }

    @Test
    @DisplayName("无 MAIN 设备：不下发、不登记，回执来了也不更新状态")
    void 无设备保持原状态() {
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        sync.onPlanChanged(event(PlanSyncEvent.Op.UPSERT));
        sync.onComplete("whatever", DEVICE_ID, true, null);

        verifyNoInteractions(downlinkPublisher, wsPushService);
        verify(medPlanMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("MQTT 禁用（downlinkPublisher=null）：安全 no-op，不写状态不推送")
    void mqtt禁用安全跳过() {
        ReflectionTestUtils.setField(sync, "downlinkPublisher", null);

        sync.onPlanChanged(event(PlanSyncEvent.Op.UPSERT));

        verify(medPlanMapper, never()).selectOne(any(LambdaQueryWrapper.class));
        verify(medPlanMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(wsPushService);
    }

    @Test
    @DisplayName("WS 未启用：状态照常落库，不推送也不报错")
    void ws未启用静默跳过() {
        when(wsProvider.getIfAvailable()).thenReturn(null);

        sync.onPlanChanged(event(PlanSyncEvent.Op.UPSERT));
        assertDoesNotThrow(() -> sync.onComplete("msg-1", DEVICE_ID, true, null));

        verify(medPlanMapper).update(any(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(wsPushService);
    }

    @Test
    @DisplayName("WS 推送抛异常被兜住：不影响状态落库、不外抛打断 MQTT 线程")
    void ws推送异常不外抛() {
        when(wsPushService.publish(any(), any(), any())).thenThrow(new RuntimeException("序列化失败"));

        sync.onPlanChanged(event(PlanSyncEvent.Op.UPSERT));
        assertDoesNotThrow(() -> sync.onComplete("msg-1", DEVICE_ID, true, null));

        verify(medPlanMapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("计划已删（update=0）：不推状态帧")
    void 计划已删不推送() {
        when(medPlanMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        sync.onPlanChanged(event(PlanSyncEvent.Op.DELETE));
        sync.onComplete("msg-1", DEVICE_ID, true, null);

        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("未知 msgId 的回执：直接忽略")
    void 未知回执忽略() {
        sync.onComplete("unknown-msg", DEVICE_ID, true, null);

        verify(medPlanMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(wsPushService);
    }

    private static PlanSyncEvent event(PlanSyncEvent.Op op) {
        return new PlanSyncEvent(PLAN_ID, ELDER_ID, op);
    }

    private static Device device() {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setElderId(ELDER_ID);
        return device;
    }

    private static MedPlan plan(String syncState) {
        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(ELDER_ID);
        plan.setSyncState(syncState);
        return plan;
    }
}

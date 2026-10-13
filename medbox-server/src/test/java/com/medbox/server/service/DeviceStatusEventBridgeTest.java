package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceEventType;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 状态跃变 → 事件流水 + WebSocket 推送（{@link DeviceStatusEventBridge}）。
 *
 * <p>这里验的是"两个出口都被正确用上"：事件写进 {@code device_event}（供事件流水接口查），
 * 状态帧推到 {@code elder:{elderId}} 主题（小程序 {@code feat/mp-device} 靠它实时刷新在线徽标）。
 * 以及两条降级路径：WS 关掉时静默跳过、设备无归属老人时只写事件不推送。
 */
class DeviceStatusEventBridgeTest {

    private static final String ELDER_ID = "u-1001";
    private static final String DEVICE_ID = "BOXA1001";

    private DeviceEventService deviceEventService;
    private WsPushService wsPushService;

    private ObjectProvider<WsPushService> provider;

    private DeviceStatusEventBridge bridge;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        deviceEventService = mock(DeviceEventService.class);
        wsPushService = mock(WsPushService.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(wsPushService);
        bridge = new DeviceStatusEventBridge(deviceEventService, provider);
    }

    @Test
    @DisplayName("上线：写 ONLINE 事件，并推 DEVICE_STATUS 帧到 elder 主题")
    void 上线写事件并推送() {
        bridge.onStatusChanged(main(ELDER_ID), OnlineState.ONLINE);

        verify(deviceEventService).record(DEVICE_ID, DeviceEventType.ONLINE, null);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(wsPushService).publish(eq("elder:" + ELDER_ID), eq(WsFrameType.DEVICE_STATUS), payload.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> frame = (Map<String, Object>) payload.getValue();
        assertEquals(DEVICE_ID, frame.get("deviceId"));
        assertEquals("MAIN", frame.get("deviceType"));
        assertEquals(Boolean.TRUE, frame.get("online"));
        assertEquals("", frame.get("parentDeviceId"));
    }

    @Test
    @DisplayName("离线：写 OFFLINE 事件，online=false")
    void 离线写事件并推送() {
        bridge.onStatusChanged(main(ELDER_ID), OnlineState.OFFLINE);

        verify(deviceEventService).record(DEVICE_ID, DeviceEventType.OFFLINE, null);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(wsPushService).publish(eq("elder:" + ELDER_ID), eq(WsFrameType.DEVICE_STATUS), payload.capture());
        assertEquals(Boolean.FALSE, ((Map<?, ?>) payload.getValue()).get("online"));
    }

    @Test
    @DisplayName("子设备：推送时带上 parentDeviceId（文档 04 第 3 章）")
    void 子设备带父设备编号() {
        Device sensor = new Device();
        sensor.setDeviceId("BOXA1001-S-CAM01");
        sensor.setDeviceType(DeviceType.CAMERA);
        sensor.setParentDeviceId(DEVICE_ID);
        sensor.setElderId(ELDER_ID);

        bridge.onStatusChanged(sensor, OnlineState.ONLINE);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(wsPushService).publish(eq("elder:" + ELDER_ID), eq(WsFrameType.DEVICE_STATUS), payload.capture());
        assertEquals(DEVICE_ID, ((Map<?, ?>) payload.getValue()).get("parentDeviceId"));
    }

    @Test
    @DisplayName("WS 未启用（medbox.ws.enabled=false）：只写事件，不推送也不报错")
    void 推送不可用时静默跳过() {
        when(provider.getIfAvailable()).thenReturn(null);

        bridge.onStatusChanged(main(ELDER_ID), OnlineState.ONLINE);

        verify(deviceEventService).record(DEVICE_ID, DeviceEventType.ONLINE, null);
        verifyNoInteractions(wsPushService);
    }

    @Test
    @DisplayName("设备无归属老人（未登记设备也能连 MQTT）：只写事件，无主题可推")
    void 无归属老人只写事件() {
        bridge.onStatusChanged(main(null), OnlineState.ONLINE);

        verify(deviceEventService).record(DEVICE_ID, DeviceEventType.ONLINE, null);
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("推送抛异常被兜住：本回调跑在 MQTT 消息线程上，不能打断上行链路")
    void 推送异常不外抛() {
        when(wsPushService.publish(any(), any(), any())).thenThrow(new RuntimeException("序列化失败"));

        assertDoesNotThrow(() -> bridge.onStatusChanged(main(ELDER_ID), OnlineState.ONLINE));
    }

    @Test
    @DisplayName("参数为 null 时静默返回，不做任何事")
    void 空参数安全() {
        assertDoesNotThrow(() -> bridge.onStatusChanged(null, OnlineState.ONLINE));
        assertDoesNotThrow(() -> bridge.onStatusChanged(main(ELDER_ID), null));

        verifyNoInteractions(deviceEventService, wsPushService);
    }

    private static Device main(String elderId) {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setDeviceType(DeviceType.MAIN);
        device.setElderId(elderId);
        return device;
    }
}

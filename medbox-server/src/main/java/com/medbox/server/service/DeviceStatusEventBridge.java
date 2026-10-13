package com.medbox.server.service;

import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceEventType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mqtt.DeviceStatusChangeListener;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 把设备在线状态的跃变接到"事件流水"与"WebSocket 推送"上（{@code feat/device-core}）。
 *
 * <p><b>两件事，两个出口</b>：
 * <ul>
 *   <li>写 {@code device_event}（{@code ONLINE} / {@code OFFLINE}）—— 供
 *       {@code GET /devices/{id}/events} 查询；</li>
 *   <li>推 {@link WsFrameType#DEVICE_STATUS} 帧到 {@code elder:{elderId}} 主题 ——
 *       小程序 {@code feat/mp-device} 的"列表在线状态实时更新"依赖它。</li>
 * </ul>
 *
 * <p><b>推送走 {@link WsPushService} 这一个出口</b>：不自己持有 {@code WebSocketSession}
 * （文档 04 第 4 章的跨分支约定）。{@code medbox.ws.enabled=false} 时
 * {@code ObjectProvider} 取不到实例，推送静默跳过，不影响上行链路。
 *
 * <p><b>全程兜异常</b>：本类跑在 MQTT 消息处理线程上，抛出去会打断整条上行链路 ——
 * 事件流水与推送都是旁路，失败只记日志。
 */
@Component
public class DeviceStatusEventBridge implements DeviceStatusChangeListener {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusEventBridge.class);

    private final DeviceEventService deviceEventService;
    private final ObjectProvider<WsPushService> wsPushService;

    public DeviceStatusEventBridge(DeviceEventService deviceEventService,
                                   ObjectProvider<WsPushService> wsPushService) {
        this.deviceEventService = deviceEventService;
        this.wsPushService = wsPushService;
    }

    @Override
    public void onStatusChanged(Device device, OnlineState newState) {
        if (device == null || newState == null) {
            return;
        }
        try {
            deviceEventService.record(device.getDeviceId(), eventTypeOf(newState), null);
            push(device, newState);
        } catch (RuntimeException ex) {
            log.warn("处理设备状态跃变副作用失败 deviceId={}：{}", device.getDeviceId(), ex.getMessage());
        }
    }

    private void push(Device device, OnlineState newState) {
        WsPushService pusher = wsPushService.getIfAvailable();
        if (pusher == null) {
            log.debug("WS 未启用，跳过 DEVICE_STATUS 推送 deviceId={}", device.getDeviceId());
            return;
        }
        String elderId = device.getElderId();
        if (elderId == null || elderId.isBlank()) {
            // 未登记设备（局域网上任何 clientId 都能连）也能连上 MQTT，
            // 但它不属于任何老人，没有可推的主题
            log.debug("设备无归属老人，跳过 DEVICE_STATUS 推送 deviceId={}", device.getDeviceId());
            return;
        }

        // payload 字段按文档 04 第 3 章：deviceId / deviceType / online / parentDeviceId（子设备时）
        pusher.publish(WsTopic.elder(elderId), WsFrameType.DEVICE_STATUS, Map.of(
                "deviceId", device.getDeviceId(),
                "deviceType", device.getDeviceType() == null ? "" : device.getDeviceType().name(),
                "online", newState == OnlineState.ONLINE,
                "parentDeviceId", device.getParentDeviceId() == null ? "" : device.getParentDeviceId()));
    }

    private static DeviceEventType eventTypeOf(OnlineState state) {
        return state == OnlineState.ONLINE ? DeviceEventType.ONLINE : DeviceEventType.OFFLINE;
    }
}

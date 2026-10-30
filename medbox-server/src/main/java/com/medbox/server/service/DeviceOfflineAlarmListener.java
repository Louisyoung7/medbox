package com.medbox.server.service;

import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mqtt.DeviceStatusChangeListener;
import org.springframework.stereotype.Component;

/**
 * 设备在线状态跃变的告警监听者（feat/alarm-device-offline）。
 *
 * <p>在 MAIN 设备 OFFLINE 跃变时委托 {@link DeviceOfflineAlarmService} 生成 {@code DEVICE_OFFLINE} 告警。
 * 作为 {@link DeviceStatusChangeListener} 实现，被自动注入所有监听者列表，
 * <b>零侵入</b>接入两条触发路径：① LWT 即时离线（{@code DeviceStatusService.markOffline}）；
 * ② 定时扫描离线（{@code DeviceOfflineScanService}）。
 *
 * <p><b>传感器隔离</b>：非 MAIN（传感器子设备）离线时，{@code DeviceOfflineAlarmService.raise} 内部首行返回，
 * 严格满足"传感器不生成 DEVICE_OFFLINE 告警"。
 *
 * <p><b>必须自己兜异常</b>：本回调跑在 MQTT 消息处理线程，抛出去会打断整条上行链路；
 * 事件流水与 WS 状态推送由 {@code DeviceStatusEventBridge} 负责，本类只管告警。
 */
@Component
public class DeviceOfflineAlarmListener implements DeviceStatusChangeListener {

    private final DeviceOfflineAlarmService alarmService;

    public DeviceOfflineAlarmListener(DeviceOfflineAlarmService alarmService) {
        this.alarmService = alarmService;
    }

    @Override
    public void onStatusChanged(Device device, OnlineState newState) {
        if (device == null || newState == null) {
            return;
        }
        if (newState != OnlineState.OFFLINE) {
            // 仅关注离线跃变；上线等其它跃变不生成离线告警
            return;
        }
        alarmService.raise(device);
    }
}

package com.medbox.server.mqtt;

import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.OnlineState;

/**
 * 设备在线状态**跃变**的监听回调（{@code feat/device-core}）。
 *
 * <p><b>为什么用回调而不是让 {@link DeviceStatusService} 直接依赖业务 Service</b>：
 * {@code mqtt} 包整包受 {@code medbox.mqtt.enabled} 条件装配，而事件流水与 WebSocket 推送
 * 是两个独立的生命周期（{@code medbox.ws.enabled} 又是一个开关）。让 {@code mqtt} 包反向
 * 依赖业务包会形成硬耦合，开关一关就启动失败。走 {@code List<监听器>} 注入（与
 * {@code MqttInboundGateway} 收 {@code List<UpMessageHandler>} 是同一套做法），
 * 没有监听器时天然是空列表，零副作用。
 *
 * <p><b>只在状态真的变了才回调</b>：主控每 60s 有心跳，若每次上行都回调一次，
 * 事件流水一天就是 1440 条噪声。跃变判定在 {@link DeviceStatusService} 内部完成，
 * 监听者不需要自己判重。
 */
public interface DeviceStatusChangeListener {

    /**
     * 设备在线状态发生跃变。
     *
     * <p><b>实现必须自己兜住异常</b>：本回调跑在 MQTT 消息处理线程上，
     * 抛出去会打断整条上行链路。事件流水 / 推送都是旁路，失败只记日志。
     *
     * @param device 发生跃变的设备（此时 {@code device.onlineState} 还是<b>旧值</b>，
     *               新值用 {@code newState} —— 避免实现方误以为实体已被刷新）
     * @param newState 跃变后的状态
     */
    void onStatusChanged(Device device, OnlineState newState);
}

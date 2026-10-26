package com.medbox.server.dto.device;

import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.dto.env.EnvRealtimeResponse;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /devices/{deviceId}} 的响应体（见文档 02 第 4 章）。
 *
 * <p><b>最新遥测 {@code latestTelemetry}</b>：温湿度 / 光照读数来自 {@code env_sample} 表，
 * 由 {@code feat/env-core} 写入与查询。详情接口经 {@code EnvSampleService.realtime(deviceId, null)}
 * 一并取回（无上报记录时为 {@code null}），结构对齐 {@code GET /env/realtime}。
 *
 * <p><b>不含仓门状态</b>：设备侧尚未上报（{@code device} 表也没有该列），等上报后再补。
 *
 * @param deviceId          药箱主控编号
 * @param deviceType        恒为 {@code MAIN}
 * @param elderId           归属老人
 * @param productKey        产品 key
 * @param timezone          设备本地时区（服药计划按此换算，见文档 06 的 2.6.1）
 * @param onlineState       在线状态
 * @param lastHeartbeat     最近一次心跳时间
 * @param createdAt         登记时间
 * @param sensors           下属传感器子设备及其在线状态
 * @param latestTelemetry   最新温湿光读数（无上报为 {@code null}），见文档 02 第 9 章
 */
public record DeviceDetailResponse(String deviceId,
                                   String deviceType,
                                   String elderId,
                                   String productKey,
                                   String timezone,
                                   String onlineState,
                                   OffsetDateTime lastHeartbeat,
                                   OffsetDateTime createdAt,
                                   List<SensorSummary> sensors,
                                   EnvRealtimeResponse latestTelemetry) {

    /** 下属传感器子设备（无独立 MQTT 连接，在线状态由主控汇总上报）。 */
    public record SensorSummary(String deviceId,
                                String deviceType,
                                String onlineState,
                                OffsetDateTime lastHeartbeat) {

        public static SensorSummary from(Device device) {
            return new SensorSummary(
                    device.getDeviceId(),
                    device.getDeviceType() == null ? null : device.getDeviceType().name(),
                    device.getOnlineState() == null ? OnlineState.OFFLINE.name() : device.getOnlineState().name(),
                    device.getLastHeartbeat());
        }
    }
}

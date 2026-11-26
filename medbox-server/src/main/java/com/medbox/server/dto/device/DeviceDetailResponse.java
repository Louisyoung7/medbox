package com.medbox.server.dto.device;

import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.dto.env.EnvRealtimeResponse;
import java.time.OffsetDateTime;

/**
 * {@code GET /devices/{deviceId}} 的响应体（见文档 02b §1.0）。
 *
 * <p><b>最新遥测 {@code latestTelemetry}</b>：温湿度 / 光照读数来自 {@code env_sample} 表，
 * 由 {@link com.medbox.server.service.EnvSampleService} 写入与查询；无上报时为 {@code null}。
 * 系统不再有传感器子设备数组与子设备在线状态。
 *
 * @param deviceId          药箱主控编号
 * @param elderId           归属老人
 * @param productKey        产品 key
 * @param timezone          设备本地时区
 * @param onlineState       在线状态
 * @param lastHeartbeat     最近一次心跳时间
 * @param createdAt         登记时间
 * @param latestTelemetry   最新温湿光读数（无上报为 {@code null}）
 */
public record DeviceDetailResponse(String deviceId,
                                   String elderId,
                                   String productKey,
                                   String timezone,
                                   String onlineState,
                                   OffsetDateTime lastHeartbeat,
                                   OffsetDateTime createdAt,
                                   EnvRealtimeResponse latestTelemetry) {

    static String stateOf(OnlineState state) {
        return state == null ? OnlineState.OFFLINE.name() : state.name();
    }
}

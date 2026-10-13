package com.medbox.server.dto.device;

import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.OnlineState;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /devices/{deviceId}} 的响应体（见文档 02 第 4 章）。
 *
 * <p><b>不含最新遥测</b>：温湿度 / 光照读数存在 {@code env_sample} 表里，由
 * {@code feat/env-core} 负责写入与查询。本分支不提前引那张表 —— 引了也查不出数据
 * （还没有设备上报过），只会让详情接口多一次必然为空的查询。等那个分支落地时
 * 在 {@code DeviceDetailResponse} 上加一个 {@code latestTelemetry} 字段即可，结构不用动。
 *
 * <p><b>不含仓门状态</b>：设备侧尚未上报（{@code device} 表也没有该列），等上报后再补。
 *
 * @param deviceId      药箱主控编号
 * @param deviceType    恒为 {@code MAIN}
 * @param elderId       归属老人
 * @param productKey    产品 key
 * @param timezone      设备本地时区（服药计划按此换算，见文档 06 的 2.6.1）
 * @param onlineState   在线状态
 * @param lastHeartbeat 最近一次心跳时间
 * @param createdAt     登记时间
 * @param sensors       下属传感器子设备及其在线状态
 */
public record DeviceDetailResponse(String deviceId,
                                   String deviceType,
                                   String elderId,
                                   String productKey,
                                   String timezone,
                                   String onlineState,
                                   OffsetDateTime lastHeartbeat,
                                   OffsetDateTime createdAt,
                                   List<SensorSummary> sensors) {

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

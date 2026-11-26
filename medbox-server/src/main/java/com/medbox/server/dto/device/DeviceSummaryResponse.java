package com.medbox.server.dto.device;

import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.OnlineState;
import java.time.OffsetDateTime;

/**
 * {@code GET /devices} 的列表项（见文档 02b §1.0）。
 *
 * <p>只列药箱主控；系统不再建模传感器子设备，列表也没有 {@code sensorCount}。
 *
 * @param deviceId      药箱主控编号
 * @param elderId       归属老人（监护多个老人时用来分组展示）
 * @param productKey    产品 key
 * @param timezone      设备本地时区
 * @param onlineState   在线状态
 * @param lastHeartbeat 最近一次心跳时间，未上线过则为 null
 */
public record DeviceSummaryResponse(String deviceId,
                                    String elderId,
                                    String productKey,
                                    String timezone,
                                    String onlineState,
                                    OffsetDateTime lastHeartbeat) {

    public static DeviceSummaryResponse from(Device device) {
        return new DeviceSummaryResponse(
                device.getDeviceId(),
                device.getElderId(),
                device.getProductKey(),
                device.getTimezone(),
                device.getOnlineState() == null ? OnlineState.OFFLINE.name() : device.getOnlineState().name(),
                device.getLastHeartbeat());
    }
}

package com.medbox.server.dto.device;

import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.OnlineState;
import java.time.OffsetDateTime;
import java.util.function.Function;

/**
 * {@code GET /devices} 的列表项（见文档 02 第 4 章）。
 *
 * <p>只列**药箱主控**，传感器子设备在 {@link DeviceDetailResponse} 里展开 ——
 * 列表页关心的是"我监护的老人各有几台药箱、还在不在线"，把传感器平铺进列表只会淹掉主信息。
 *
 * @param deviceId      药箱主控编号
 * @param deviceType    恒为 {@code MAIN}
 * @param elderId       归属老人（监护多个老人时用来分组展示）
 * @param productKey    产品 key
 * @param timezone      设备本地时区
 * @param onlineState   在线状态
 * @param lastHeartbeat 最近一次心跳时间，未上线过则为 null
 * @param sensorCount   下属传感器子设备数量（列表页显示"3 个传感器"这类摘要用）
 */
public record DeviceSummaryResponse(String deviceId,
                                    String deviceType,
                                    String elderId,
                                    String productKey,
                                    String timezone,
                                    String onlineState,
                                    OffsetDateTime lastHeartbeat,
                                    int sensorCount) {

    /**
     * 从实体转换。
     *
     * @param sensorCountOf 统计该主控下属子设备数量的函数：列表接口一次性批量查（避免 N+1），
     *                      所以由 Service 传入，而不是让本 DTO 去查库
     */
    public static DeviceSummaryResponse from(Device device, Function<String, Integer> sensorCountOf) {
        return new DeviceSummaryResponse(
                device.getDeviceId(),
                device.getDeviceType() == null ? null : device.getDeviceType().name(),
                device.getElderId(),
                device.getProductKey(),
                device.getTimezone(),
                device.getOnlineState() == null ? OnlineState.OFFLINE.name() : device.getOnlineState().name(),
                device.getLastHeartbeat(),
                sensorCountOf.apply(device.getDeviceId()));
    }
}

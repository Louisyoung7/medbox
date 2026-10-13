package com.medbox.server.dto.device;

import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.OnlineState;
import java.time.OffsetDateTime;

/**
 * {@code GET /devices/{deviceId}/status} 的响应体（见文档 02 第 4 章）。
 *
 * <p><b>只返回在线状态与最后心跳</b>：文档表格里写的"在线/仓门"中的仓门状态，
 * 设备侧尚未上报、{@code device} 表也没有对应列，故本次不做，等上报后加字段。
 *
 * <p>与 {@link DeviceDetailResponse} 的区别：这个是**高频轮询**用的轻量接口
 * （小程序进详情页时刷新状态），字段刻意压到最少；档案类信息走详情接口。
 *
 * @param deviceId      药箱主控编号
 * @param onlineState   在线状态
 * @param lastHeartbeat 最近一次心跳时间，未上线过则为 null
 */
public record DeviceStatusResponse(String deviceId,
                                   String onlineState,
                                   OffsetDateTime lastHeartbeat) {

    public static DeviceStatusResponse from(Device device) {
        return new DeviceStatusResponse(
                device.getDeviceId(),
                device.getOnlineState() == null ? OnlineState.OFFLINE.name() : device.getOnlineState().name(),
                device.getLastHeartbeat());
    }
}

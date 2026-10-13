package com.medbox.server.dto.device;

import com.medbox.server.domain.DeviceEvent;
import com.medbox.server.domain.enums.DeviceEventType;
import java.time.OffsetDateTime;

/**
 * {@code GET /devices/{deviceId}/events} 的列表项（见文档 06 的 2.16）。
 *
 * <p>事件流水**无业务号**：只在"某台设备的流水"这个作用域内被引用，
 * 因此直接返回自增主键作为 {@code eventId}，不额外造 {@code event_id} 业务列。
 *
 * @param eventId    自增主键
 * @param deviceId   设备编号（子设备记自己的编号，不折算成主控）
 * @param eventType  事件类型
 * @param detail     补充描述，无则为 null（受全局 {@code non_null} 影响不出现在 JSON 里）
 * @param createdAt  事件发生时间（UTC）
 */
public record DeviceEventResponse(Long eventId,
                                  String deviceId,
                                  String eventType,
                                  String detail,
                                  OffsetDateTime createdAt) {

    public static DeviceEventResponse from(DeviceEvent event) {
        return new DeviceEventResponse(
                event.getId(),
                event.getDeviceId(),
                event.getEventType() == null ? null : event.getEventType().name(),
                event.getDetail(),
                event.getCreatedAt());
    }

    /** 该事件是否是状态类事件（上线 / 离线）—— 小程序用来决定是否刷新在线徽标。 */
    public boolean isStatusEvent() {
        return eventType != null
                && (DeviceEventType.ONLINE.name().equals(eventType) || DeviceEventType.OFFLINE.name().equals(eventType));
    }
}

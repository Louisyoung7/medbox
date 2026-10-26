package com.medbox.server.dto.alarm;

import com.medbox.server.domain.Alarm;
import java.time.OffsetDateTime;

/**
 * 告警列表 / 详情共用响应（见文档 02 第 8 章）。
 *
 * <p>字段直接映射 {@link Alarm} 全列；{@code createdAt} 为服务端写入时间（UTC instant）。
 * 关联名称（药名 / 计划名）本期告警列表不额外 join，客户端用 {@code medicineId}/{@code planId}
 * 自行查名（与 WS {@code ALARM} 帧口径一致）。
 */
public record AlarmResponse(String alarmId,
                           String elderId,
                           String deviceId,
                           String planId,
                           String medicineId,
                           String type,
                           String level,
                           String message,
                           Boolean readState,
                           String handleState,
                           OffsetDateTime createdAt) {

    public static AlarmResponse from(Alarm a) {
        if (a == null) {
            return null;
        }
        return new AlarmResponse(
                a.getAlarmId(), a.getElderId(), a.getDeviceId(), a.getPlanId(),
                a.getMedicineId(), a.getType(), a.getLevel(), a.getMessage(),
                a.getReadState(), a.getHandleState(), a.getCreatedAt());
    }
}

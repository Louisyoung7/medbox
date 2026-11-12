package com.medbox.server.dto.alarm;

import com.medbox.server.domain.Alarm;
import java.time.OffsetDateTime;

/**
 * 告警列表 / 详情共用响应（见文档 02f）。
 *
 * <p>字段直接映射 {@link Alarm} 全列；MISS 告警带 {@code cellNo}，message 只提格号与时间，
 * 不再关联药品。
 */
public record AlarmResponse(String alarmId,
                           String elderId,
                           String deviceId,
                           String planId,
                           Integer cellNo,
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
                a.getCellNo(), a.getType(), a.getLevel(), a.getMessage(),
                a.getReadState(), a.getHandleState(), a.getCreatedAt());
    }
}

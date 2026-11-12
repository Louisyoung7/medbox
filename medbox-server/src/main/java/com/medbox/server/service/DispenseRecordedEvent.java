package com.medbox.server.service;

import java.time.OffsetDateTime;
import lombok.Getter;

/**
 * 服药记录已落库（事务提交后）的领域事件（见 07 §1.6）。
 *
 * <p>由 {@code DispenseConsumeService} 在 {@code @Transactional} 方法里 {@code publishEvent}，
 * Spring 在事务提交后（{@code AFTER_COMMIT}）派发给监听器。事件携带判定所需的最小字段：
 * {@code planId / cellNo / planTime}，供漏服判定按格比对；不推 WebSocket、不生成告警。
 */
@Getter
public class DispenseRecordedEvent {

    private final String recordId;
    private final String elderId;
    private final String planId;
    private final Integer cellNo;
    private final OffsetDateTime planTime;
    private final OffsetDateTime actualTime;
    private final Boolean onTime;
    private final String deviceId;
    private final String captureId;
    private final String takenResult;

    public DispenseRecordedEvent(String recordId, String elderId, String planId, Integer cellNo,
                                 OffsetDateTime planTime, OffsetDateTime actualTime, Boolean onTime,
                                 String deviceId, String captureId, String takenResult) {
        this.recordId = recordId;
        this.elderId = elderId;
        this.planId = planId;
        this.cellNo = cellNo;
        this.planTime = planTime;
        this.actualTime = actualTime;
        this.onTime = onTime;
        this.deviceId = deviceId;
        this.captureId = captureId;
        this.takenResult = takenResult;
    }
}

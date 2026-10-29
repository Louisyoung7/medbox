package com.medbox.server.service;

import java.time.OffsetDateTime;
import lombok.Getter;

/**
 * 服药记录已落库（事务提交后）的领域事件。
 *
 * <p>由 {@code DispenseConsumeService} 在 {@code @Transactional} 方法里 {@code publishEvent}，
 * Spring 在事务提交后（{@code AFTER_COMMIT}）派发给监听器。
 *
 * <p><b>下游消费者（本分支不实现，仅预留）</b>：
 * <ul>
 *   <li>{@code feat/miss-alarm}：按 {@code planId}/{@code planTime}/{@code planItemId} 判定某槽是否缺服；</li>
 *   <li>（错服监测 {@code feat/wrong-drug-alarm} 已移除，不再生成 WRONG_DRUG 告警）。</li>
 * </ul>
 *
 * <p>事件只携带判定所需的最小字段；需要更多上下文的消费者可自行按 {@code recordId} 回查 {@code med_record}。
 */
@Getter
public class DispenseRecordedEvent {

    private final String recordId;
    private final String elderId;
    private final String planId;
    private final String planItemId;
    private final String medicineId;
    private final OffsetDateTime planTime;
    private final OffsetDateTime actualTime;
    private final Boolean onTime;
    private final Double confidence;
    private final String deviceId;
    private final String captureId;

    public DispenseRecordedEvent(String recordId, String elderId, String planId, String planItemId,
                                 String medicineId, OffsetDateTime planTime, OffsetDateTime actualTime,
                                 Boolean onTime, Double confidence,
                                 String deviceId, String captureId) {
        this.recordId = recordId;
        this.elderId = elderId;
        this.planId = planId;
        this.planItemId = planItemId;
        this.medicineId = medicineId;
        this.planTime = planTime;
        this.actualTime = actualTime;
        this.onTime = onTime;
        this.confidence = confidence;
        this.deviceId = deviceId;
        this.captureId = captureId;
    }
}

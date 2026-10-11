package com.medbox.server.mqtt.payload;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 上行·服药事件 {@code up/event/dispense}（见文档 03 第 3 章）。
 *
 * <p><b>拿错药也走这里</b>，用 {@code wrongDrug} 表达；{@code up/event/error} 只报设备侧异常。
 * 药箱<b>不划分仓位</b>，所以没有 {@code slotNo} —— 是否拿对由摄像头识别
 * {@code medicineId} 是否等于计划药品判定。
 *
 * <p>{@code onTime} 设备侧也会算一份，但<b>只用于即时声光反馈</b>；入库依据由后端按
 * "计划时间 + 容忍时长"重算（{@code feat/dispense-consume}），改阈值无需重烧固件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DispenseEventPayload extends BaseUpPayload {

    private String planId;

    private String planItemId;

    private String medicineId;

    private String actualDose;

    private String unit;

    /** 识别结果与计划药品明确不符才为 true；{@code confidence} 低时设备侧应先置 false。 */
    private Boolean wrongDrug;

    /** 是否按时（仅即时反馈口径，服务端会重算）。 */
    private Boolean onTime;

    /** 摄像头识别置信度；低于阈值不判错服，只标 LOW_CONFIDENCE 交人工复核。 */
    private Double confidence;

    /** {@code CAMERA} 摄像头识别 / {@code MANUAL} 手工补录。 */
    private String source;

    /** 本次服药抓拍 ID（{@code capture.capture_id}）；当前摄像头全量抓拍，正常服药也不为空。 */
    private String imageId;
}

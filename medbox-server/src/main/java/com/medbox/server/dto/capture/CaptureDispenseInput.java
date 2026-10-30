package com.medbox.server.dto.capture;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 摄像头 HTTP 上报纸机参数 → 判定核心统一入口（feat/backend-capture-event）。
 *
 * <p>由 {@code CaptureService.upload} 组装后转发给 {@code DispenseConsumeService}，
 * 使摄像头 HTTP 抓拍上报与旧 MQTT {@code up/event/dispense} 共用同一判定核心
 * （就近槽 / 三级容忍 / 库存扣减口径一致）。
 */
@Data
public class CaptureDispenseInput {

    /** 摄像头子设备 deviceId（由 parent_device_id 反查 MAIN 药箱）。 */
    private String cameraDeviceId;

    /** 本次抓拍业务号（capture.capture_id），关联留证图。 */
    private String captureId;

    private String planId;

    private String planItemId;

    private String medicineId;

    /** 实际服用剂量，原样落库。 */
    private String actualDose;

    /** 剂量单位（如 片/粒），本期仅接收、不持久化（med_record 无该列，与旧 MQTT 行为一致）。 */
    private String unit;

    /** 摄像头上报三态结论 TAKEN/ABSENT/UNCERTAIN（可空）。 */
    private String faceResult;

    /** 摄像头自报置信度 0~1（可空）。 */
    private BigDecimal confidence;

    /** 抓拍时刻（UTC）。 */
    private OffsetDateTime capturedAt;

    /** 来源，默认 CAMERA。 */
    private String source;
}

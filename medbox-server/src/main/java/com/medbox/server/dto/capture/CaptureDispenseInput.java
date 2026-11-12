package com.medbox.server.dto.capture;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 设备 HTTP 上报参数 → 判定核心统一入口（见 07 §1）。
 *
 * <p>由 {@code CaptureService.upload} 组装后转发给 {@code DispenseConsumeService}：
 * 格号是事件与计划槽的唯一对应依据，后端不接收、不记录格内药品与剂量。
 */
@Data
public class CaptureDispenseInput {

    /** 摄像头子设备 deviceId（由 parent_device_id 反查 MAIN 药箱）。 */
    private String cameraDeviceId;

    /** 本次抓拍业务号（capture.capture_id），关联留证图。 */
    private String captureId;

    private String planId;

    /** 药格格号 1 / 2 / 3（planId 非空时必带）。 */
    private Integer cellNo;

    /** 设备端侧取药结论 TAKEN / UNTAKEN（可空，缺失兜底 UNTAKEN）。 */
    private String takenResult;

    /** 抓拍时刻（UTC）。 */
    private OffsetDateTime capturedAt;

    /** 来源，默认 CAMERA。 */
    private String source;
}

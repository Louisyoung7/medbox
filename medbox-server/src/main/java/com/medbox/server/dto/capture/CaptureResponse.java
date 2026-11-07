package com.medbox.server.dto.capture;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 抓拍元数据响应（列表项与详情共用，见文档 02 的 4.1）。
 *
 * <p>只含元数据，不内嵌文件流；{@code fileToken} 供小程序 {@code <image src>} 拼接
 * {@code /captures/{captureId}/file?token=...} 使用（5 分钟有效）。{@code filePath} 绝不返回。
 */
@Data
public class CaptureResponse {

    private final String captureId;
    private final String deviceId;
    private final String elderId;
    private final String medicineId;
    private final String planId;
    private final String reason;
    private final String takenResult;
    private final String mime;
    private final Integer sizeBytes;
    private final OffsetDateTime capturedAt;
    private final OffsetDateTime viewedAt;
    private final OffsetDateTime expireAt;
    private final String fileToken;

    public CaptureResponse(String captureId, String deviceId, String elderId, String medicineId,
                           String planId, String reason, String takenResult, String mime, Integer sizeBytes,
                           OffsetDateTime capturedAt, OffsetDateTime viewedAt,
                           OffsetDateTime expireAt, String fileToken) {
        this.captureId = captureId;
        this.deviceId = deviceId;
        this.elderId = elderId;
        this.medicineId = medicineId;
        this.planId = planId;
        this.reason = reason;
        this.takenResult = takenResult;
        this.mime = mime;
        this.sizeBytes = sizeBytes;
        this.capturedAt = capturedAt;
        this.viewedAt = viewedAt;
        this.expireAt = expireAt;
        this.fileToken = fileToken;
    }
}

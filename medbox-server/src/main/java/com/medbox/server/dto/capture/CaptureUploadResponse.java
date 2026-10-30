package com.medbox.server.dto.capture;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 抓拍上传响应（见文档 02 的 4.1）。
 *
 * <pre>{@code { "captureId": "cap-1001", "expireAt": "2026-11-03T00:04:00Z" } }</pre>
 */
@Data
public class CaptureUploadResponse {

    /** 抓拍业务号（{@code cap-} 前缀）。 */
    private final String captureId;

    /** 清理时刻（UTC）：未读 = captured_at + retentionDaysUnread。 */
    private final OffsetDateTime expireAt;

    /** 本次上报若生成了服药记录（{@code planId} 非空且判定核心成功），回带其业务号；否则 null。 */
    private final String recordId;

    public CaptureUploadResponse(String captureId, OffsetDateTime expireAt) {
        this(captureId, expireAt, null);
    }

    public CaptureUploadResponse(String captureId, OffsetDateTime expireAt, String recordId) {
        this.captureId = captureId;
        this.expireAt = expireAt;
        this.recordId = recordId;
    }
}

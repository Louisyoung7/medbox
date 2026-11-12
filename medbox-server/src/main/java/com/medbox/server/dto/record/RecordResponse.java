package com.medbox.server.dto.record;

import com.medbox.server.domain.MedRecord;
import java.time.OffsetDateTime;

/**
 * 服药记录回显（列表项 / 详情共用，见文档 02e）。
 *
 * <p>一条记录 = 某计划的某号格在某次计划时间窗内的一次取药事实，只带 {@code cellNo}，
 * 不带药品、剂量；{@code planName} 由后端 join 计划名返回，{@code hasCapture} 供缩略图占位。
 *
 * @param recordId     记录业务 ID（{@code mr-} 前缀）
 * @param elderId      归属老人业务 ID
 * @param planId       所属计划业务 ID
 * @param cellNo       药格格号 1 / 2 / 3（缺失表示未识别）
 * @param planName     计划名（join {@code med_plan.name}）
 * @param planTime     该格计划服药时间（UTC），该格无排程时为 null
 * @param actualTime   实际服药时间（UTC）
 * @param onTime       是否按时
 * @param takenResult  取药结论：TAKEN / UNTAKEN（可空）
 * @param source       来源：CAMERA / MANUAL
 * @param captureId    抓拍留证业务 ID（无则为 null）
 * @param hasCapture   是否存在抓拍留证
 * @param deviceId     上报设备业务 ID
 * @param createdAt    创建时间（UTC）
 */
public record RecordResponse(
        String recordId,
        String elderId,
        String planId,
        Integer cellNo,
        String planName,
        OffsetDateTime planTime,
        OffsetDateTime actualTime,
        Boolean onTime,
        String takenResult,
        String source,
        String captureId,
        boolean hasCapture,
        String deviceId,
        OffsetDateTime createdAt) {

    public static RecordResponse from(MedRecord r, String planName) {
        return new RecordResponse(
                r.getRecordId(),
                r.getElderId(),
                r.getPlanId(),
                r.getCellNo(),
                planName,
                r.getPlanTime(),
                r.getActualTime(),
                r.getOnTime(),
                r.getTakenResult(),
                r.getSource(),
                r.getCaptureId(),
                r.getCaptureId() != null,
                r.getDeviceId(),
                r.getCreatedAt());
    }
}

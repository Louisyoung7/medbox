package com.medbox.server.dto.record;

import com.medbox.server.domain.MedRecord;
import java.time.OffsetDateTime;

/**
 * 服药记录回显（列表项 / 详情共用，见文档 02 第 7 章）。
 *
 * <p><b>关联字段</b>：{@code planName}/{@code medicineName} 由后端 join 计划名 / 药名返回；
 * {@code captureId} 回传抓拍留证业务 ID（文件流取用归 P1 {@code capture-core}，本期只给 ID），
 * {@code hasCapture} 供小程序缩略图占位判断。
 *
 * @param recordId     记录业务 ID（{@code mr-} 前缀）
 * @param elderId      归属老人业务 ID
 * @param planId       所属计划业务 ID
 * @param planName     计划名（join {@code med_plan.name}）
 * @param medicineId   药品业务 ID
 * @param medicineName 药品名称（join {@code medicine.name}）
 * @param actualDose   实际服用剂量
 * @param planTime     计划服药时间（UTC），无匹配槽时为 null
 * @param actualTime   实际服药时间（UTC）
 * @param onTime       是否按时
 * @param wrongDrug    是否错服
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
        String planName,
        String medicineId,
        String medicineName,
        String actualDose,
        OffsetDateTime planTime,
        OffsetDateTime actualTime,
        Boolean onTime,
        Boolean wrongDrug,
        String source,
        String captureId,
        boolean hasCapture,
        String deviceId,
        OffsetDateTime createdAt) {

    public static RecordResponse from(MedRecord r, String planName, String medicineName) {
        return new RecordResponse(
                r.getRecordId(),
                r.getElderId(),
                r.getPlanId(),
                planName,
                r.getMedicineId(),
                medicineName,
                r.getActualDose(),
                r.getPlanTime(),
                r.getActualTime(),
                r.getOnTime(),
                r.getWrongDrug(),
                r.getSource(),
                r.getCaptureId(),
                r.getCaptureId() != null,
                r.getDeviceId(),
                r.getCreatedAt());
    }
}

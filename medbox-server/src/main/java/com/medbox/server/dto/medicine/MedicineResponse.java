package com.medbox.server.dto.medicine;
import com.medbox.server.domain.Medicine;
import java.time.OffsetDateTime;

  /**
   * {@code GET /medicines/{medicineId}} 的响应体，也是 {@code POST
  /medicines} 保存后的回显
   * （见文档 02 第 5 章）。
   *
   * <p>与 {@link MedicineSummaryResponse}
  的区别：多出药品说明、储存条件、创建时间三个
   * 详情字段（列表不放长文本）。
   *
   * @param medicineId       药品业务 ID
   * @param name             药品名称
   * @param dosePerTime      默认单次剂量
   * @param unit             单位
   * @param description      药品说明（监护人手动填写）
   * @param storageCond      储存条件文字说明
   * @param ownerElderId     归属老人业务 ID（必填）
   * @param createdAt        创建时间（UTC）
   */

public record MedicineResponse(
    String medicineId,
    String name,
    String dosePerTime,
    String unit,
    String description,
    String storageCond,
    String ownerElderId,
    OffsetDateTime createdAt
){
    public static MedicineResponse from(Medicine medicine){
        return new MedicineResponse(
            medicine.getMedicineId(),
            medicine.getName(),
            medicine.getDosePerTime(),
            medicine.getUnit(),
            medicine.getDescription(),
            medicine.getStorageCond(),
            medicine.getOwnerElderId(),
            medicine.getCreatedAt()
        );
    }
}


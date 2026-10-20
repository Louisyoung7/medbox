package com.medbox.server.dto.medicine;
import com.medbox.server.domain.Medicine;
  /**
   * {@code GET /medicines} 的列表项（见文档 02 第 5 章）。
   *
   * <p><b>不含禁忌 / 储存条件</b>：{@code contraindication} 是
  TEXT、{@code storageCond} 是展示长文本，
   * 字典列表按分页返回几十条，逐条塞长文本只会撑大响应；这两个字段放详
  情接口
   * （{@link MedicineResponse}）。
   *
   * <p>{@code ownerElderId}
  用来区分「公共字典（null）」与「某老人的私有药品」——
   * 小程序据此给私有药品打上归属标记。
   *
   * @param medicineId   药品业务 ID
   * @param name         药品名称
   * @param spec         规格
   * @param unit         单位
   * @param dosePerTime  默认单次剂量
   * @param ownerElderId 归属老人业务 ID；null = 公共药品字典
   */
public record MedicineSummaryResponse(
    String medicineId,
    String name,
    String spec,
    String unit,
    String dosePerTime,
    String ownerElderId
){
    public static MedicineSummaryResponse from(Medicine medicine){
        return new MedicineSummaryResponse(
            medicine.getMedicineId(),
            medicine.getName(),
            medicine.getSpec(),
            medicine.getUnit(),
            medicine.getDosePerTime(),
            medicine.getOwnerElderId()
        );
    }
}

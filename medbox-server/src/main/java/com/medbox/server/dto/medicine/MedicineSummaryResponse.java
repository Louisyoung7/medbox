package com.medbox.server.dto.medicine;
import com.medbox.server.domain.Medicine;
  /**
   * {@code GET /medicines} 的列表项（见文档 02 第 5 章）。
   *
   * <p><b>不含药品说明 / 储存条件</b>：{@code description} 是
  TEXT、{@code storageCond} 是展示长文本，
   * 字典列表按分页返回几十条，逐条塞长文本只会撑大响应；这两个字段放详
  情接口
   * （{@link MedicineResponse}）。
   *
   * <p>{@code ownerElderId}
  恒非空，小程序据此显示药品所属老人。
   *
   * @param medicineId   药品业务 ID
   * @param name         药品名称
   * @param unit         单位
   * @param dosePerTime  默认单次剂量
   * @param ownerElderId 归属老人业务 ID（必填）
   */
public record MedicineSummaryResponse(
    String medicineId,
    String name,
    String unit,
    String dosePerTime,
    String ownerElderId
){
    public static MedicineSummaryResponse from(Medicine medicine){
        return new MedicineSummaryResponse(
            medicine.getMedicineId(),
            medicine.getName(),
            medicine.getUnit(),
            medicine.getDosePerTime(),
            medicine.getOwnerElderId()
        );
    }
}

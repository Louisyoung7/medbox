package com.medbox.server.dto.medicine;

import com.medbox.server.domain.MedicineStock;
import java.time.OffsetDateTime;
import java.time.LocalDate;
  /**
   * {@code GET /devices/{deviceId}/stocks} 的列表项，也是 {@code PUT
  .../stocks/{medicineId}} 与
   * {@code POST .../stocks/{medicineId}/in} 保存后的回显（见文档 02 第
  5 章、06 的 2.5）。
   *
   * <p><b>库存不划分仓位 / 格子</b>：一条记录 =
  某台药箱里的某一种药（{@code device_id + medicine_id}
   * 唯一），数量、生产日期、有效期都挂在这条记录上。
   *
   * <p><b>{@code medicineName} 是关联查出来的</b>：库存表只存 {@code
  medicine_id}，列表要显示药名，
   * 由 Service 批量按 {@code medicineId} 查药名后填进来（{@link
  #from(MedicineStock, String)} 的第二参），
   * 避免列表逐条查库的 N+1。
   *
   * <p><b>生产/有效期是纯日期 {@link LocalDate}</b>，只到天（06 的
  2.5）；更新时间才用时间戳
   * {@link OffsetDateTime}。到期判定唯一依据是 {@code
  expiryDate}（系统不识别标签 / RFID）。
   *
   * @param stockId        库存业务 ID（如 {@code s-1001}）
   * @param deviceId       药箱主控 ID
   * @param medicineId     药品业务 ID
   * @param medicineName   药品名称（关联查出，供列表展示）
   * @param quantity       库存数量
   * @param productionDate 生产日期（可空，监护人手动录入）
   * @param expiryDate     有效期至（可空，监护人手动录入）
   * @param updatedAt      最近更新时间（UTC）
   */

  public record StockItemResponse(
    String stockId,
    String deviceId,
    String medicineId,
    String medicineName,
    Integer quantity,
    LocalDate productionDate,
    LocalDate expiryDate,
    OffsetDateTime updatedAt
  ){
    public static StockItemResponse from(MedicineStock stock, String medicineName){
        return new StockItemResponse(
            stock.getStockId(),
            stock.getDeviceId(),
            stock.getMedicineId(),
            medicineName,
            stock.getQuantity(),
            stock.getProductionDate(),
            stock.getExpiryDate(),
            stock.getUpdatedAt()
        );
    }
  }

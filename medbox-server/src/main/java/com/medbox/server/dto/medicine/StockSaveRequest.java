package com.medbox.server.dto.medicine;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

 /**
   * {@code PUT /devices/{deviceId}/stocks/{medicineId}}
  的请求体（见文档 02 第 5 章）。
   *
   * <p><b>录入 /
  更新某药品库存</b>：数量必填；生产日期与有效期由监护人手动录入，
   * 可空（不确定时允许先只记数量，之后再补）。注意这里传的是**当前库存
  总量**，
   * 不是增减量 —— 增减走 {@code POST .../in}（见 {@link
  StockInRequest}）。
   *
   * @param quantity       库存数量（整体覆盖为这个值）
   * @param productionDate 生产日期（可空）
   * @param expiryDate     有效期至（可空）
   */


  public record StockSaveRequest(
    @NotNull(message="quantity 不能为空")
    @Min(value=0,message="quantity 必须大于等于 0")
    Integer quantity,
    LocalDate productionDate,
    LocalDate expiryDate
  ){

  }

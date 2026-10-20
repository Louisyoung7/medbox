package com.medbox.server.dto.medicine;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

  /**
   * {@code POST /devices/{deviceId}/stocks/{medicineId}/in}
  的请求体（见文档 02 第 5 章）。
   *
   * <p><b>补药入库（增加库存）</b>：{@code quantity}
  是**增量**，在现有库存上累加；同时可顺手
   * 更新生产日期 / 有效期（新进一批药时日期会变）。
   *
   * @param quantity       本次补入的数量（正数，累加到现有库存上）
   * @param productionDate 生产日期（可空，不传则沿用原值）
   * @param expiryDate     有效期至（可空，不传则沿用原值）
   */

  public record StockInRequest(
    @NotNull(message="quantity 不能为空")
    @Positive(message="quantity 必须大于 0")
    Integer quantity,
    LocalDate productionDate,
    LocalDate expiryDate
  ){
     
  }

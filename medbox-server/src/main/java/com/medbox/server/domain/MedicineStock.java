package com.medbox.server.domain;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;
import java.time.LocalDate;

  /**
   * 药品库存，对应 {@code medicine_stock} 表（见文档 06 的 2.5，由 V1 建）。
   *
   * <p><b>不划分仓位/格子</b>：库存按「哪个药箱里的哪种药」管理（{@code
  device_id} + {@code medicine_id}
   * 唯一约束），数量、生产日期、有效期都挂在这条记录上。
   *
   * <p><b>{@code elder_id} 是冗余字段</b>：冗余 {@code
  device.elder_id}，便于按老人直接查库存，
   * 免一次 join device。
   *
   * <p><b>生产/有效期是 {@code DATE}，不是时间戳</b>：录入只到天（文档 06 的
  2.5）。这是本工程
   * 第一次用到纯日期列，用 {@link java.time.LocalDate} 映射；TIMESTAMPTZ
  才用 {@link OffsetDateTime}
   * （见 {@link Device#getCreatedAt()}）。
   *
   * <p><b>业务 ID {@code stockId}</b>（如 {@code s-1001}）走 {@code
  medicine_stock_biz_id_seq} 序列（V8 建），
   * 取号方法在第 3 步的 MedicineStockMapper 里。
   */
@Data
@TableName("medicine_stock")
public class MedicineStock{
    @TableId(type=IdType.AUTO)
    private Long id;
    private String stockId;/** 业务 ID，如 {@code s-1001}。 */
    private String deviceId;/** 药箱 ID。 */
    private String elderId;/** 老人 ID。 */
    private String medicineId;/** 药品 ID。 */
    private Integer quantity;/** 库存数量。 */
    private LocalDate productionDate;/** 生产日期。 */
    private LocalDate expiryDate;/** 有效期。 */
    private OffsetDateTime updatedAt;/** 更新时间。 */
    
}
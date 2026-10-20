package com.medbox.server.domain;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

  /**
   * 药品档案，对应 {@code medicine} 表（见文档 06 的 2.4，由 V1 建）。
   *
   * <p><b>可见范围</b>：{@code owner_elder_id}
  为空表示「公共药品字典」，全体用户可见只读；
   * 非空表示该老人的私有药品，仅其监护人可见。这个判定放 Service 层（02 第 5
  章），实体不掺业务。
   *
   * <p><b>主键必须 {@code IdType.AUTO}</b>：表主键是 BIGSERIAL，MyBatis-Plus
  默认雪花 ID
   * （ASSIGN_ID）会往自增列里塞一个巨大的值（与 {@link User} / {@link
  Device} 同一个坑）。
   *
   * <p><b>业务 ID {@code medicineId}</b>（如 {@code
  m-1001}）不由数据库生成，走
   * {@code medicine_biz_id_seq} 序列（V8 建），取号方法在第 3 步的
  MedicineMapper 里。
   */

@Data
@TableName("medicine")
public class Medicine{
    @TableId(type=IdType.AUTO)
    private Long id;
    private String medicineId;/** 业务 ID，如 {@code m-1001}。 */
    private String ownerElderId;/** 所属老人 ID，为空表示公共药品字典。 */
    private String name;/** 药品名称。 */
    private String spec;/** 药品规格。 */
    private String dosePerTime;/** 默认单次剂量（计划明细未指定时取此值）。 */
    private String unit;/** 药品单位。 */
    private String contraindication;/** 禁忌。 */
    private String storageCond;/** 存储条件。 */
    private OffsetDateTime createdAt;/** 创建时间。 */

}


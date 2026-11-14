package com.medbox.server.dto.medicine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
  /**
   * {@code POST /medicines} 的请求体（见文档 02 第 5 章）。
   *
   * <p><b>一个接口承担新建与更新两种动作</b>（文档里写的"新建/更新"）：
   * {@code medicineId} 留空 = 新建，业务号由服务端取 {@code
  medicine_biz_id_seq} 生成
   * {@code m-1001} 这类；带了 {@code medicineId} =
  更新，按业务号定位这条档案。
   * 所以这里没有单独的 {@code PUT /medicines/{medicineId}}。
   *
   * <p><b>{@code ownerElderId} 决定可见范围</b>（见 06 的
  2.4）：药品必归属某位老人，创建时必填，留空 → 40001；
   * 更新时传了也会被忽略（归属不可变）。是否有权写这个老人
   * （{@code WRITE_MEDICINE}）在 Service 层用 {@code
  require(elderId, WRITE_MEDICINE)} 判定。
   *
   * @param medicineId       药品业务 ID；新建留空，更新时必填
   * @param name             药品名称
   * @param dosePerTime      默认单次剂量（计划明细未指定时取此值，如
  "1"）
   * @param unit             单位（片/粒/ml）
   * @param contraindication 禁忌（TEXT，可长文本）
   * @param storageCond      储存条件文字说明（如
  "遮光、密封、阴凉处"），仅供展示
   * @param ownerElderId     归属老人业务 ID；创建必填，更新时忽略
   */
public record MedicineSaveRequest(
    @Size(max=64,message="medicineId 超长")
    String medicineId,

    @NotBlank(message="name 不能为空")
    @Size(max=128,message="name 超长")
    String name,

    @Size(max=32,message="dosePerTime 超长")
    String dosePerTime,

    @Size(max=16,message="unit 超长")
    String unit,

    @Size(max=1024,message="contraindication 超长")
    String contraindication,

    @Size(max=128,message="storageCond 超长")
    String storageCond,

    @Size(max=64,message="ownerElderId 超长")
    String ownerElderId
){

}



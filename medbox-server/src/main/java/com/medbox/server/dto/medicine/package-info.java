  /**
   * 药品档案与库存管理的请求 / 响应体（见文档 02 第 5 章、06 的 2.4 /
  2.5）。
   *
   * <p>响应体用 record（字段名即 JSON 字段，与文档一致）；请求体用
  record + Jakarta
   * 校验注解（{@code @NotBlank} / {@code @Size} / {@code @NotNull}
  等），由 Controller 的
   * {@code @Valid} 触发校验。
   *
   * <p><b>一个包装两个子域</b>：药品档案（{@code
  Medicine*}）与库存（{@code Stock*}）在
   * {@code feat/medicine-core} 里是同一个分支（{@code medicine-crud} +
  {@code stock-manage}），
   * 库存记录又以 {@code medicine_id}
  强关联药品，拆成两个包反而要多一份互相 import。
   */
package com.medbox.server.dto.medicine;
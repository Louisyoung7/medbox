  /**
   * 药品档案的请求 / 响应体（见文档 02c、06 的 2.4）。
   *
   * <p>响应体用 record（字段名即 JSON 字段，与文档一致）；请求体用
  record + Jakarta
   * 校验注解（{@code @NotBlank} / {@code @Size} / {@code @NotNull}
  等），由 Controller 的
   * {@code @Valid} 触发校验。
   */
package com.medbox.server.dto.medicine;
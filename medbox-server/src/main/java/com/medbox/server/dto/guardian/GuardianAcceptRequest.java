package com.medbox.server.dto.guardian;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
   * {@code PATCH /users/guardians/{relationId}/accept}
  的请求体（见文档 02 第 3 章）。
   *
   * <p>{@code role} 是 String 而不是 {@code RelationRole}
  枚举：非法值在 Service 里解析成
   * {@code null} 后按 40001 返回友好提示，而不是让 Jackson
  直接抛解析异常。
   */
public record GuardianAcceptRequest(
    @NotBlank(message="role 不能为空")
    @Size(max=16,message="role 超长")
    String role
){
    
}
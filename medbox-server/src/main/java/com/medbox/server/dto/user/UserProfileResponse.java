package com.medbox.server.dto.user;

/**
 * {@code GET /users/me} 的响应体（见文档 02 第 3 章）。
 *
 * <p>字段名即 JSON 字段名，与文档示例严格一致；受全局
 * {@code spring.jackson.default-property-inclusion: non_null} 影响，值为 null 的字段不出现在 JSON 里。
 *
 * <p>小程序只用 {@code userId} / {@code role} / {@code name} / {@code phone}（{@code role} 决定角色视图），
 * 其余字段随后端实现追加、前端整份缓存不挑字段。
 *
 * @param userId   当前登录用户的业务 ID
 * @param role     账号角色：{@code ELDER} / {@code GUARDIAN}
 * @param name     展示名
 * @param phone    手机号
 * @param username 登录账号（可空）
 */
public record UserProfileResponse(String userId,
                                  String role,
                                  String name,
                                  String phone,
                                  String username) {
}

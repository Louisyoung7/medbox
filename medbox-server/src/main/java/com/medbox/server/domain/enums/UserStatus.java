package com.medbox.server.domain.enums;

/**
 * 用户账号状态（{@code user.status}，见文档 06 的 2.1、02 的 3.1）。
 *
 * <p><b>注销是软删除</b>：置 {@link #DELETED} 而不是删行 —— 让历史数据里引用的 {@code user_id}
 * 继续成立（用药记录留痕），同时由注销接口把 {@code phone} / {@code username} 置 {@code NULL}
 * 释放唯一键，使同一手机号可以重新注册。
 */
public enum UserStatus {

    /** 正常：可登录、可刷新、可访问自己的数据。 */
    ACTIVE,

    /** 已注销：不可登录、不可刷新；access token 最长 2 小时后自然失效（不引入黑名单）。 */
    DELETED
}

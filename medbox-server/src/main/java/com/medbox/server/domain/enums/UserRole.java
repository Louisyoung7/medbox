package com.medbox.server.domain.enums;

import java.util.Locale;

/**
 * 用户角色（{@code user.role}，见文档 06 的 2.1）。
 *
 * <p>注册接口**只接受这两种**（文档 02 第 3 章）：社区护理 / 家庭医生不是小程序注册角色，
 * 它们是 {@code guardian_relation.role}（FAMILY / DOCTOR / NURSE），由老人在确认监护关系时指定。
 */
public enum UserRole {

    /** 老人本人（设备近场使用者，只读本人数据）。 */
    ELDER,

    /** 监护人 / 子女（对 ACTIVE 监护关系下的老人可读可写）。 */
    GUARDIAN;

    /** 宽松解析（忽略大小写与首尾空格）；不是合法角色返回 {@code null}，由调用方决定报错码。 */
    public static UserRole parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UserRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

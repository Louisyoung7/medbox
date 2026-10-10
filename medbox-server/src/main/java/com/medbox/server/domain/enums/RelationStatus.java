package com.medbox.server.domain.enums;

import java.util.Locale;

/**
 * 监护关系状态（{@code guardian_relation.status}，见文档 06 的 2.2）。
 *
 * <p>两步生效：监护人发起申请 → {@link #PENDING}；老人端确认 → {@link #ACTIVE}。
 * 监护人对该老人的读写权限**仅在 ACTIVE 期间有效**：
 * <ul>
 *   <li>{@code PENDING} → 访问该老人数据返回 <b>40302</b>（关系未生效）；</li>
 *   <li>{@code REVOKED} → 返回 <b>40301</b>（与"无关系"同码，避免泄漏"曾经有过关系"）。</li>
 * </ul>
 */
public enum RelationStatus {

    /** 监护人已申请，待老人端确认。 */
    PENDING,

    /** 已生效：监护人对该老人的权限以此为准。 */
    ACTIVE,

    /** 已解除：老人端拒绝、任一方解除，或重放保护触发的连带撤销。 */
    REVOKED;

    /** 宽松解析（忽略大小写与首尾空格）；不是合法状态返回 {@code null}，由调用方决定报错码。 */
    public static RelationStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RelationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

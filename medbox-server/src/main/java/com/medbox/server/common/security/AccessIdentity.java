package com.medbox.server.common.security;

/**
 * 权限判定结果（校验顺序第 ②③④ 步的产物，见文档 02 的 1.3）。
 *
 * <p>{@code AccessService.require(...)} 通过判定后返回它，业务代码据此知道"当前请求者是谁、
 * 以什么身份访问哪位老人的数据"；判定不通过直接抛 {@code BizException}（40301 / 40302），拿不到本对象。
 *
 * <p><b>为什么 {@code relationRole} 用字符串而不是 {@code RelationRole} 枚举</b>：与
 * {@link AuthContext.CurrentUser#role()} 同一口径 —— 避免 common 层依赖 domain 枚举，
 * 取值恒为 {@code FAMILY / DOCTOR / NURSE}（{@code kind == GUARDIAN} 时非空，
 * 且已在 {@code AccessService} 里把 NULL 兜底成 {@code FAMILY}）。
 *
 * @param userId      当前登录用户的业务 ID
 * @param userRole    当前登录用户的账号角色：{@code ELDER} / {@code GUARDIAN}
 * @param elderId     本次访问的资源归属老人（业务 user_id）
 * @param kind        访问身份：老人本人 / 监护人
 * @param relationRole 监护关系角色（{@code kind == GUARDIAN} 时非空；{@code SELF} 时为 {@code null}）
 */
public record AccessIdentity(String userId,
                             String userRole,
                             String elderId,
                             Kind kind,
                             String relationRole) {

    /** 访问身份。 */
    public enum Kind {

        /** 老人本人访问自己的数据（不需要监护关系）。 */
        SELF,

        /** 监护人访问其监护老人的数据（要求关系存在且 ACTIVE）。 */
        GUARDIAN
    }

    /** 老人本人身份。 */
    public static AccessIdentity self(String userId) {
        return new AccessIdentity(userId, "ELDER", userId, Kind.SELF, null);
    }

    /** 监护人身份；{@code relationRole} 传 NULL 时按 {@code FAMILY} 兜底。 */
    public static AccessIdentity guardian(String userId, String elderId, String relationRole) {
        String role = relationRole == null || relationRole.isBlank() ? "FAMILY" : relationRole;
        return new AccessIdentity(userId, "GUARDIAN", elderId, Kind.GUARDIAN, role);
    }

    /** 是否以老人本人身份访问。 */
    public boolean isSelf() {
        return kind == Kind.SELF;
    }
}

package com.medbox.server.common.security;

/**
 * 权限判定结果（校验顺序第 ②③④ 步的产物，见文档 02 的 1.3）。
 *
 * <p>{@code AccessService.require(...)} 通过判定后返回它，业务代码据此知道"当前请求者是谁、
 * 以什么身份访问哪位老人的数据"；判定不通过直接抛 {@code BizException}（40301 / 40302），拿不到本对象。
 *
 * <p>监护人已统一为单一 {@code GUARDIAN} 角色（文档 10 §1.2），不再携带细分的关系角色，
 * 故本记录只保留访问身份 {@code kind}，不含 {@code relationRole}。
 *
 * @param userId    当前登录用户的业务 ID
 * @param userRole  当前登录用户的账号角色：{@code ELDER} / {@code GUARDIAN}
 * @param elderId   本次访问的资源归属老人（业务 user_id）
 * @param kind      访问身份：老人本人 / 监护人
 */
public record AccessIdentity(String userId,
                             String userRole,
                             String elderId,
                             Kind kind) {

    /** 访问身份。 */
    public enum Kind {

        /** 老人本人访问自己的数据（不需要监护关系）。 */
        SELF,

        /** 监护人访问其监护老人的数据（要求关系存在且 ACTIVE）。 */
        GUARDIAN
    }

    /** 老人本人身份。 */
    public static AccessIdentity self(String userId) {
        return new AccessIdentity(userId, "ELDER", userId, Kind.SELF);
    }

    /** 监护人身份（统一单一角色，无细分关系角色）。 */
    public static AccessIdentity guardian(String userId, String elderId) {
        return new AccessIdentity(userId, "GUARDIAN", elderId, Kind.GUARDIAN);
    }

    /** 是否以老人本人身份访问。 */
    public boolean isSelf() {
        return kind == Kind.SELF;
    }
}

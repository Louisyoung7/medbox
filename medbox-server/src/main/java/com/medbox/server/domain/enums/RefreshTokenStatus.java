package com.medbox.server.domain.enums;

/**
 * refresh token 状态（{@code refresh_token.status}，见文档 06 的 2.13）。
 */
public enum RefreshTokenStatus {

    /** 生效中：可用于换取新的一对 token。 */
    ACTIVE,

    /** 已被轮换：已被换新顶替，再次使用视为重放（撤销该用户全部 refresh token）。 */
    ROTATED,

    /** 已撤销：登出 / 重放保护 / 人工吊销。 */
    REVOKED
}

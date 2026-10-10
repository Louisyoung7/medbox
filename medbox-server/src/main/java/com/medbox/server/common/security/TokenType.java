package com.medbox.server.common.security;

/**
 * 令牌类型（JWT 的 {@code typ} claim）。
 *
 * <p>access 与 refresh 用同一把密钥签发，因此**必须靠 typ 区分**：拿 refresh token 当 access token
 * 用（或反之）一律按未登录处理，避免"长效的 refresh 顶替短效的 access"绕过有效期。
 */
public enum TokenType {

    /** access token：2 小时，用于业务接口鉴权。 */
    ACCESS,

    /** refresh token：7 天，只能用来换新的一对 token，轮换制。 */
    REFRESH
}

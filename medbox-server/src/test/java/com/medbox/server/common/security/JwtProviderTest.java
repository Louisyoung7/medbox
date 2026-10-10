package com.medbox.server.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

/** JWT 签发 / 解析（不启容器、不连库）。 */
class JwtProviderTest {

    private static final String SECRET = "unit-test-jwt-secret-unit-test-jwt-secret";
    private static final long ACCESS_TTL = 7200L;
    private static final long REFRESH_TTL = 604800L;

    private final JwtProvider provider = new JwtProvider(SECRET, ACCESS_TTL, REFRESH_TTL);

    @Test
    void 签发后能解析出用户与角色() {
        String token = provider.issue("u-1001", "GUARDIAN", TokenType.ACCESS);

        JwtPayload payload = provider.parse(token, TokenType.ACCESS);
        assertEquals("u-1001", payload.userId());
        assertEquals("GUARDIAN", payload.role());
        assertEquals(TokenType.ACCESS, payload.type());
        assertEquals(ACCESS_TTL, provider.accessTtlSeconds());
        assertEquals(REFRESH_TTL, provider.refreshTtlSeconds());
    }

    @Test
    void refresh_token_不能当_access_token_用() {
        String refreshToken = provider.issue("u-1001", "ELDER", TokenType.REFRESH);

        BizException ex = assertThrows(BizException.class, () -> provider.parse(refreshToken, TokenType.ACCESS));
        assertEquals(ErrorCode.UNAUTHORIZED.code(), ex.code());
    }

    @Test
    void 篡改签名后解析失败() {
        String token = provider.issue("u-1001", "ELDER", TokenType.ACCESS);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "B" : "A");
        assertNotEquals(token, tampered);

        BizException ex = assertThrows(BizException.class, () -> provider.parse(tampered, TokenType.ACCESS));
        assertEquals(ErrorCode.UNAUTHORIZED.code(), ex.code());
    }

    @Test
    void 过期令牌解析失败() {
        JwtProvider expiredProvider = new JwtProvider(SECRET, -1L, REFRESH_TTL);
        String token = expiredProvider.issue("u-1001", "ELDER", TokenType.ACCESS);

        BizException ex = assertThrows(BizException.class, () -> expiredProvider.parse(token, TokenType.ACCESS));
        assertEquals(ErrorCode.UNAUTHORIZED.code(), ex.code());
    }

    @Test
    void 空令牌解析失败() {
        BizException ex = assertThrows(BizException.class, () -> provider.parse("  ", TokenType.ACCESS));
        assertEquals(ErrorCode.UNAUTHORIZED.code(), ex.code());
    }

    @Test
    void 密钥过短时启动即失败() {
        assertThrows(IllegalStateException.class, () -> new JwtProvider("too-short", ACCESS_TTL, REFRESH_TTL));
    }
}

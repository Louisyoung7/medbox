package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.RefreshToken;
import com.medbox.server.domain.enums.RefreshTokenStatus;
import com.medbox.server.mapper.RefreshTokenMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** refresh token 轮换与重放保护（Mockito 打桩 Mapper，不启容器、不连库）。 */
class RefreshTokenServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-unit-test-jwt-secret";

    private final JwtProvider jwtProvider = new JwtProvider(SECRET, 7200L, 604800L);
    private final RefreshTokenMapper mapper = mock(RefreshTokenMapper.class);
    private final RefreshTokenService service = new RefreshTokenService(mapper, jwtProvider);

    @Test
    void 轮换后旧记录被置为_ROTATED() {
        String raw = jwtProvider.issue("u-1001", "GUARDIAN", TokenType.REFRESH);
        String jti = jwtProvider.parse(raw, TokenType.REFRESH).jti();
        RefreshToken row = activeRow("u-1001", jti, raw);
        when(mapper.selectOne(any())).thenReturn(row);

        RefreshTokenService.Rotation rotation = service.rotate(raw);

        assertEquals("u-1001", rotation.userId());
        assertEquals(jti, rotation.rotatedTokenId());
        assertEquals(RefreshTokenStatus.ROTATED, row.getStatus());
        verify(mapper).updateById(row);
        verify(mapper, never()).revokeActiveByUserId(any(), any());
    }

    @Test
    void 重放已轮换的_token_会撤销该用户全部会话() {
        String raw = jwtProvider.issue("u-1001", "GUARDIAN", TokenType.REFRESH);
        String jti = jwtProvider.parse(raw, TokenType.REFRESH).jti();
        RefreshToken row = activeRow("u-1001", jti, raw);
        row.setStatus(RefreshTokenStatus.ROTATED);
        when(mapper.selectOne(any())).thenReturn(row);

        BizException ex = assertThrows(BizException.class, () -> service.rotate(raw));

        assertEquals(ErrorCode.UNAUTHORIZED.code(), ex.code());
        verify(mapper).revokeActiveByUserId("u-1001", RefreshTokenStatus.REVOKED);
    }

    @Test
    void 已过期的_token_直接拒绝_不牵连其它会话() {
        String raw = jwtProvider.issue("u-1001", "GUARDIAN", TokenType.REFRESH);
        String jti = jwtProvider.parse(raw, TokenType.REFRESH).jti();
        RefreshToken row = activeRow("u-1001", jti, raw);
        row.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        when(mapper.selectOne(any())).thenReturn(row);

        BizException ex = assertThrows(BizException.class, () -> service.rotate(raw));

        assertEquals(ErrorCode.UNAUTHORIZED.code(), ex.code());
        verify(mapper, never()).revokeActiveByUserId(any(), any());
    }

    @Test
    void 哈希不吻合视为泄漏_撤销全部会话() {
        String raw = jwtProvider.issue("u-1001", "GUARDIAN", TokenType.REFRESH);
        String jti = jwtProvider.parse(raw, TokenType.REFRESH).jti();
        RefreshToken row = activeRow("u-1001", jti, raw);
        row.setTokenHash("0000000000000000000000000000000000000000000000000000000000000000");
        when(mapper.selectOne(any())).thenReturn(row);

        BizException ex = assertThrows(BizException.class, () -> service.rotate(raw));

        assertEquals(ErrorCode.UNAUTHORIZED.code(), ex.code());
        verify(mapper).revokeActiveByUserId("u-1001", RefreshTokenStatus.REVOKED);
    }

    @Test
    void 登记新_token_时回填旧记录的接替者() {
        String oldRaw = jwtProvider.issue("u-1001", "GUARDIAN", TokenType.REFRESH);
        String oldJti = jwtProvider.parse(oldRaw, TokenType.REFRESH).jti();
        String newRaw = jwtProvider.issue("u-1001", "GUARDIAN", TokenType.REFRESH);
        String newJti = jwtProvider.parse(newRaw, TokenType.REFRESH).jti();
        RefreshToken oldRow = activeRow("u-1001", oldJti, oldRaw);
        when(mapper.selectOne(any())).thenReturn(oldRow);

        service.issue("u-1001", newRaw, oldJti);

        verify(mapper).insert(any(RefreshToken.class));
        assertEquals(RefreshTokenStatus.ROTATED, oldRow.getStatus());
        assertEquals(newJti, oldRow.getReplacedBy());
        verify(mapper).updateById(oldRow);
    }

    @Test
    void 库里不存明文_只存哈希() {
        String raw = jwtProvider.issue("u-1001", "GUARDIAN", TokenType.REFRESH);
        RefreshToken row = activeRow("u-1001", jwtProvider.parse(raw, TokenType.REFRESH).jti(), raw);

        assertEquals(RefreshTokenService.sha256Hex(raw), row.getTokenHash());
        assertEquals(64, row.getTokenHash().length(), "SHA-256 十六进制 64 位");
    }

    private static RefreshToken activeRow(String userId, String tokenId, String rawToken) {
        RefreshToken row = new RefreshToken();
        row.setTokenId(tokenId);
        row.setUserId(userId);
        row.setTokenHash(RefreshTokenService.sha256Hex(rawToken));
        row.setStatus(RefreshTokenStatus.ACTIVE);
        row.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7));
        return row;
    }
}

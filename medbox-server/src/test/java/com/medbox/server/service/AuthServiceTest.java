package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.PasswordHasher;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.dto.auth.LoginRequest;
import com.medbox.server.dto.auth.RegisterRequest;
import com.medbox.server.dto.auth.TokenResponse;
import com.medbox.server.mapper.UserMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 注册 / 登录 / 刷新的业务口径（全部用 Mockito 打桩，不启容器、不连库）。 */
class AuthServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-unit-test-jwt-secret";

    private final UserMapper userMapper = mock(UserMapper.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final JwtProvider jwtProvider = new JwtProvider(SECRET, 7200L, 604800L);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final AuthService authService =
            new AuthService(userMapper, refreshTokenService, idempotencyService, jwtProvider, passwordHasher);

    @Test
    void 注册_role_不在枚举内报_40001() {
        BizException ex = assertThrows(BizException.class,
                () -> authService.register(registerRequest("NURSE"), null));
        assertEquals(ErrorCode.BAD_REQUEST.code(), ex.code());
    }

    @Test
    void 注册_手机号已存在报_40001() {
        when(idempotencyService.replay(any(), any(), any())).thenReturn(Optional.empty());
        when(userMapper.selectOne(any())).thenReturn(existingUser());

        BizException ex = assertThrows(BizException.class,
                () -> authService.register(registerRequest("GUARDIAN"), null));
        assertEquals(ErrorCode.BAD_REQUEST.code(), ex.code());
        assertEquals("手机号已注册", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void 注册成功即登录_返回令牌与角色() {
        when(idempotencyService.replay(any(), any(), any())).thenReturn(Optional.empty());
        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.nextUserId()).thenReturn("u-1001");
        when(passwordHasher.hash(anyString())).thenReturn("$2a$10$hashed");

        TokenResponse response = authService.register(registerRequest("GUARDIAN"), null);

        assertEquals("u-1001", response.userId());
        assertEquals("GUARDIAN", response.role());
        assertEquals(7200L, response.expiresIn());
        assertEquals("u-1001", jwtProvider.parse(response.token(),
                com.medbox.server.common.security.TokenType.ACCESS).userId());
        verify(refreshTokenService).issue(eq("u-1001"), anyString(), isNull());
    }

    @Test
    void 命中幂等时回放首次响应_且不再建号() {
        TokenResponse first = new TokenResponse("access-1", "refresh-1", "u-1001", "GUARDIAN", 7200L);
        when(idempotencyService.replay(any(), any(), any())).thenReturn(Optional.of(first));

        TokenResponse response = authService.register(registerRequest("GUARDIAN"), "8f2c1a9e3b7d4c1a9f0e2d5b6a7c8f10");

        assertEquals("access-1", response.token());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void 登录_账号不存在与密码错误都报_40102() {
        when(userMapper.selectOne(any())).thenReturn(null);
        BizException notFound = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest("13800001234", "123456")));
        assertEquals(ErrorCode.BAD_CREDENTIALS.code(), notFound.code());
        verify(passwordHasher).matchesDummy(anyString());

        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);
        BizException wrongPassword = assertThrows(BizException.class,
                () -> authService.login(new LoginRequest("13800001234", "wrong")));
        assertEquals(ErrorCode.BAD_CREDENTIALS.code(), wrongPassword.code());
    }

    @Test
    void 登录成功签发令牌() {
        when(userMapper.selectOne(any())).thenReturn(existingUser());
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(true);

        TokenResponse response = authService.login(new LoginRequest("13800001234", "123456"));

        assertEquals("u-1001", response.userId());
        assertEquals("ELDER", response.role());
    }

    @Test
    void 刷新时旧_token_已作废则报_40101() {
        when(refreshTokenService.rotate(anyString()))
                .thenThrow(BizException.of(ErrorCode.UNAUTHORIZED));

        BizException ex = assertThrows(BizException.class, () -> authService.refresh("expired"));
        assertEquals(ErrorCode.UNAUTHORIZED.code(), ex.code());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void 刷新成功返回新的一对令牌() {
        when(refreshTokenService.rotate(anyString()))
                .thenReturn(new RefreshTokenService.Rotation("u-1001", "old-jti"));
        when(userMapper.selectOne(any())).thenReturn(existingUser());

        TokenResponse response = authService.refresh("a-valid-refresh-token");

        assertEquals("u-1001", response.userId());
        assertEquals(7200L, response.expiresIn());
        verify(refreshTokenService).issue(eq("u-1001"), anyString(), eq("old-jti"));
    }

    @Test
    void 未带_X_Request_Id_时不查幂等() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.nextUserId()).thenReturn("u-1002");
        when(passwordHasher.hash(anyString())).thenReturn("$2a$10$hashed");

        TokenResponse response = authService.register(registerRequest("ELDER"), null);

        assertEquals("u-1002", response.userId());
        verify(idempotencyService, never()).save(any(), any(), any(), any());
    }

    private static RegisterRequest registerRequest(String role) {
        return new RegisterRequest("13800001234", "123456", role, null, "李四");
    }

    private static User existingUser() {
        User user = new User();
        user.setId(1L);
        user.setUserId("u-1001");
        user.setPhone("13800001234");
        user.setRole(UserRole.ELDER);
        user.setPasswordHash("$2a$10$hashed");
        return user;
    }
}

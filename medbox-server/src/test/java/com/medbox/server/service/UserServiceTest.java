package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.PasswordHasher;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.domain.enums.UserStatus;
import com.medbox.server.dto.user.DeleteAccountRequest;
import com.medbox.server.dto.user.DeleteAccountResponse;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 账号注销的业务口径（07 清单 {@code feat/backend-user-delete}，见文档 02 的 3.1）。
 *
 * <p>纯 Mockito，不启容器、不连库：验的是**判定顺序与连带动作**（软删 → 撤销 refresh →
 * 双向解除关系），SQL 本身留给真机联调（{@code UserDeleteWebIntegrationTest} 打完整链路）。
 */
class UserServiceTest {

    private static final String USER_ID = "u-1001";

    private final UserMapper userMapper = mock(UserMapper.class);
    private final GuardianRelationMapper guardianRelationMapper = mock(GuardianRelationMapper.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final AccessService accessService = mock(AccessService.class);
    private final UserService userService =
            new UserService(userMapper, guardianRelationMapper, refreshTokenService, passwordHasher, accessService);

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("未登录 → 40101（注销必须先登录，接口不能挂在放行的 /auth/** 下）")
    void 未登录注销报_40101() {
        BizException ex = assertThrows(BizException.class,
                () -> userService.deleteSelf(new DeleteAccountRequest("123456")));
        assertEquals(40101, ex.code());
        verify(userMapper, never()).markDeleted(any(), any(), any());
    }

    @Test
    @DisplayName("password 缺失 / 空串 / 请求体为 null → 40001")
    void 密码缺失报_40001() {
        login();

        assertEquals(40001, assertThrows(BizException.class,
                () -> userService.deleteSelf(new DeleteAccountRequest(""))).code());
        assertEquals(40001, assertThrows(BizException.class,
                () -> userService.deleteSelf(new DeleteAccountRequest("   "))).code());
        assertEquals(40001, assertThrows(BizException.class,
                () -> userService.deleteSelf(null)).code());
    }

    @Test
    @DisplayName("账号不存在 → 40101（不回 40401，避免泄漏账号是否曾存在）")
    void 账号不存在报_40101() {
        login();
        when(userMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> userService.deleteSelf(new DeleteAccountRequest("123456")));
        assertEquals(40101, ex.code());
    }

    @Test
    @DisplayName("账号已注销 → 40101（重复提交时不泄漏状态）")
    void 已注销再注销报_40101() {
        login();
        when(userMapper.selectOne(any())).thenReturn(deletedUser());

        BizException ex = assertThrows(BizException.class,
                () -> userService.deleteSelf(new DeleteAccountRequest("123456")));
        assertEquals(40101, ex.code());
        verify(userMapper, never()).markDeleted(any(), any(), any());
    }

    @Test
    @DisplayName("密码不符 → 40102，且不执行任何删除动作")
    void 密码错误报_40102() {
        login();
        when(userMapper.selectOne(any())).thenReturn(activeUser());
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> userService.deleteSelf(new DeleteAccountRequest("wrong")));
        assertEquals(40102, ex.code());
        verify(userMapper, never()).markDeleted(any(), any(), any());
        verify(refreshTokenService, never()).revokeAll(anyString());
        verify(guardianRelationMapper, never()).revokeByUserId(any(), any());
    }

    @Test
    @DisplayName("注销成功：软删 + 置空登录标识 + 撤销 refresh + 双向解除监护关系")
    void 注销成功连带处理() {
        login();
        when(userMapper.selectOne(any())).thenReturn(activeUser());
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(true);
        when(userMapper.markDeleted(any(), any(), any())).thenReturn(1);

        DeleteAccountResponse response = userService.deleteSelf(new DeleteAccountRequest("123456"));

        assertEquals(USER_ID, response.userId());
        assertNotNull(response.deletedAt());

        ArgumentCaptor<UserStatus> statusCaptor = ArgumentCaptor.forClass(UserStatus.class);
        ArgumentCaptor<OffsetDateTime> timeCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(userMapper).markDeleted(eq(USER_ID), statusCaptor.capture(), timeCaptor.capture());
        assertEquals(UserStatus.DELETED, statusCaptor.getValue());

        verify(refreshTokenService).revokeAll(USER_ID);
        verify(guardianRelationMapper).revokeByUserId(USER_ID, RelationStatus.REVOKED);
    }

    @Test
    @DisplayName("已注销账号调 /users/me → 40101")
    void 已注销账号查资料报_40101() {
        login();
        when(userMapper.selectOne(any())).thenReturn(deletedUser());

        assertEquals(40101, assertThrows(BizException.class, () -> userService.me()).code());
    }

    private static void login() {
        AuthContext.set(new AuthContext.CurrentUser(USER_ID, "GUARDIAN"));
    }

    private static User activeUser() {
        User user = baseUser();
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private static User deletedUser() {
        User user = baseUser();
        user.setStatus(UserStatus.DELETED);
        user.setDeletedAt(OffsetDateTime.parse("2026-10-14T08:30:00Z"));
        return user;
    }

    private static User baseUser() {
        User user = new User();
        user.setId(1L);
        user.setUserId(USER_ID);
        user.setPhone("13800001234");
        user.setRole(UserRole.GUARDIAN);
        user.setPasswordHash("$2a$10$hashed");
        return user;
    }
}

package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.PasswordHasher;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserStatus;
import com.medbox.server.dto.user.DeleteAccountRequest;
import com.medbox.server.dto.user.DeleteAccountResponse;
import com.medbox.server.dto.user.UserProfileResponse;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户资料与账号注销（见文档 02 第 3 章）。
 *
 * <p>{@code GET /users/me} 此前由 {@code feat/backend-auth} 有意留空，本分支（{@code feat/backend-authz}）
 * 一并交付 —— 小程序启动时靠它做**冷启动静默续期**（隔天打开时 access 已过期、refresh 仍有效）。
 *
 * <p>这里只查"当前登录用户自己"，不涉及他人资源，因此不需要 {@link AccessService} 的权限判定；
 * 未登录由 {@link AuthContext#required()} 抛 40101。
 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final GuardianRelationMapper guardianRelationMapper;
    private final RefreshTokenService refreshTokenService;
    private final PasswordHasher passwordHasher;

    public UserService(UserMapper userMapper,
                       GuardianRelationMapper guardianRelationMapper,
                       RefreshTokenService refreshTokenService,
                       PasswordHasher passwordHasher) {
        this.userMapper = userMapper;
        this.guardianRelationMapper = guardianRelationMapper;
        this.refreshTokenService = refreshTokenService;
        this.passwordHasher = passwordHasher;
    }

    /**
     * 当前登录用户的资料与角色。
     *
     * @throws com.medbox.server.common.exception.BizException 40101 未登录 / 40401 账号已不存在
     */
    public UserProfileResponse me() {
        AuthContext.CurrentUser current = AuthContext.required();
        User user = findByUserId(current.userId());
        BizAssert.notFound(user, "当前账号不存在，请重新登录");
        BizAssert.unauthorizedIf(isDeleted(user), "账号已注销，请重新注册");
        return new UserProfileResponse(
                user.getUserId(),
                user.getRole() == null ? null : user.getRole().name(),
                user.getName(),
                user.getPhone(),
                user.getUsername());
    }

    /**
     * 注销**当前登录账号**（软删除，见文档 02 的 3.1）。
     *
     * <p>判定顺序（即优先级）：① 未登录 40101 → ② {@code password} 缺失 40001 →
     * ③ 账号不存在或已注销 40101（不回 40401，避免泄漏"账号曾存在"）→
     * ④ 密码不符 40102（**不注销**）→ ⑤ 通过则注销。
     *
     * <p>注销做四件事，全在同一个事务里：软删用户行（并置空 {@code phone} / {@code username}
     * 释放唯一键）→ 撤销该用户全部 refresh token → 双向解除监护关系 → 返回注销时间。
     * <b>业务数据（设备 / 药品 / 计划 / 记录 / 抓拍）一律不动</b> —— 用药记录需留痕。
     *
     * <p>注意：access token 是**无状态**的，注销不会让它立刻失效（最长 2 小时后自然过期，
     * 靠 refresh 已撤销兜底）；这是 02 的 3.1 明确接受的口径，不引入黑名单。
     *
     * @throws com.medbox.server.common.exception.BizException 40101 / 40001 / 40102
     */
    @Transactional
    public DeleteAccountResponse deleteSelf(DeleteAccountRequest request) {
        AuthContext.CurrentUser current = AuthContext.required();
        BizAssert.notBlank(request == null ? null : request.password(), "password 不能为空");

        User user = findByUserId(current.userId());
        BizAssert.unauthorizedIf(user == null || isDeleted(user), "账号已注销或不存在，请重新登录");

        BizAssert.isTrue(passwordHasher.matches(request.password(), user.getPasswordHash()),
                ErrorCode.BAD_CREDENTIALS);

        OffsetDateTime deletedAt = OffsetDateTime.now(ZoneOffset.UTC);
        userMapper.markDeleted(user.getUserId(), UserStatus.DELETED, deletedAt);
        refreshTokenService.revokeAll(user.getUserId());
        guardianRelationMapper.revokeByUserId(user.getUserId(), RelationStatus.REVOKED);
        return new DeleteAccountResponse(user.getUserId(), deletedAt);
    }

    private User findByUserId(String userId) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUserId, userId));
    }

    /** 已注销的账号一律按"不可用"处理（{@code status} 为 null 视为历史数据，按未注销处理）。 */
    private static boolean isDeleted(User user) {
        return user != null && user.getStatus() == UserStatus.DELETED;
    }
}

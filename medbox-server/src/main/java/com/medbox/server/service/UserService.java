package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.domain.User;
import com.medbox.server.dto.user.UserProfileResponse;
import com.medbox.server.mapper.UserMapper;
import org.springframework.stereotype.Service;

/**
 * 用户资料查询（见文档 02 第 3 章）。
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

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 当前登录用户的资料与角色。
     *
     * @throws com.medbox.server.common.exception.BizException 40101 未登录 / 40401 账号已不存在
     */
    public UserProfileResponse me() {
        AuthContext.CurrentUser current = AuthContext.required();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUserId, current.userId()));
        BizAssert.notFound(user, "当前账号不存在，请重新登录");
        return new UserProfileResponse(
                user.getUserId(),
                user.getRole() == null ? null : user.getRole().name(),
                user.getName(),
                user.getPhone(),
                user.getUsername());
    }
}

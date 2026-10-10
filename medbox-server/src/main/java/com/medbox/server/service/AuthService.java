package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.PasswordHasher;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.dto.auth.LoginRequest;
import com.medbox.server.dto.auth.RegisterRequest;
import com.medbox.server.dto.auth.TokenResponse;
import com.medbox.server.mapper.UserMapper;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 认证业务：注册 / 登录 / 刷新（见文档 02 第 3 章）。
 *
 * <p>三条铁律：① 密码只以 BCrypt 落库，登录失败**统一 40102**（不区分"账号不存在 / 密码错误"）；
 * ② access 2 小时、refresh 7 天且轮换；③ 注册走 {@code X-Request-Id} 幂等，重复提交只建一个账号。
 */
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final RefreshTokenService refreshTokenService;
    private final IdempotencyService idempotencyService;
    private final JwtProvider jwtProvider;
    private final PasswordHasher passwordHasher;

    public AuthService(UserMapper userMapper,
                       RefreshTokenService refreshTokenService,
                       IdempotencyService idempotencyService,
                       JwtProvider jwtProvider,
                       PasswordHasher passwordHasher) {
        this.userMapper = userMapper;
        this.refreshTokenService = refreshTokenService;
        this.idempotencyService = idempotencyService;
        this.jwtProvider = jwtProvider;
        this.passwordHasher = passwordHasher;
    }

    /**
     * 注册（成功即登录）。
     *
     * <p>幂等回放放在所有校验**之前**：否则重复提交会先撞上"手机号已注册"，拿不回首次响应。
     *
     * @param requestId 客户端 {@code X-Request-Id}（已 sanitize，可为 null）
     */
    public TokenResponse register(RegisterRequest request, String requestId) {
        UserRole role = UserRole.parse(request.role());
        BizAssert.badRequestIf(role == null, "role 只能是 ELDER 或 GUARDIAN");

        String fingerprint = idempotencyService.fingerprint(
                request.phone(), role.name(), request.username(), request.name());
        Optional<TokenResponse> replayed =
                idempotencyService.replay(IdempotencyService.ENDPOINT_AUTH_REGISTER, requestId, fingerprint);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        BizAssert.badRequestIf(findByPhone(request.phone()) != null, "手机号已注册");
        BizAssert.badRequestIf(request.username() != null && findByUsername(request.username()) != null,
                "用户名已被占用");

        User user = new User();
        user.setUserId(userMapper.nextUserId());
        user.setPhone(request.phone());
        user.setUsername(request.username());
        user.setName(request.name());
        user.setPasswordHash(passwordHasher.hash(request.password()));
        user.setRole(role);
        insertUser(user);

        TokenResponse response = issueTokens(user);
        if (requestId != null) {
            idempotencyService.save(IdempotencyService.ENDPOINT_AUTH_REGISTER, requestId, fingerprint, response);
        }
        return response;
    }

    /** 登录：{@code account} 可以是手机号或用户名。 */
    public TokenResponse login(LoginRequest request) {
        User user = findByAccount(request.account());
        if (user == null) {
            // 陪跑一次 BCrypt：让"账号不存在"与"密码错误"耗时相当，避免被拿来枚举已注册手机号
            passwordHasher.matchesDummy(request.password());
            throw BizException.of(ErrorCode.BAD_CREDENTIALS);
        }
        BizAssert.isTrue(passwordHasher.matches(request.password(), user.getPasswordHash()),
                ErrorCode.BAD_CREDENTIALS);
        return issueTokens(user);
    }

    /** 刷新：旧的 refresh token 立即作废（轮换），重放会撤销该用户全部会话。 */
    public TokenResponse refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(rawRefreshToken);
        User user = findByUserId(rotation.userId());
        BizAssert.unauthorizedIf(user == null, "账号不存在，请重新登录");

        String role = roleName(user);
        String newRefreshToken = jwtProvider.issue(user.getUserId(), role, TokenType.REFRESH);
        refreshTokenService.issue(user.getUserId(), newRefreshToken, rotation.rotatedTokenId());
        return new TokenResponse(
                jwtProvider.issue(user.getUserId(), role, TokenType.ACCESS),
                newRefreshToken,
                user.getUserId(),
                role,
                jwtProvider.accessTtlSeconds());
    }

    /** 签发一对 token：refresh 先落库（轮换记录），再返回响应。 */
    private TokenResponse issueTokens(User user) {
        String role = roleName(user);
        String refreshToken = jwtProvider.issue(user.getUserId(), role, TokenType.REFRESH);
        refreshTokenService.issue(user.getUserId(), refreshToken, null);
        return new TokenResponse(
                jwtProvider.issue(user.getUserId(), role, TokenType.ACCESS),
                refreshToken,
                user.getUserId(),
                role,
                jwtProvider.accessTtlSeconds());
    }

    private void insertUser(User user) {
        try {
            userMapper.insert(user);
        } catch (Exception ex) {
            // 并发注册：唯一键冲突（phone / username）落在这一层，翻译成 40001
            if (isUniqueViolation(ex)) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "手机号或用户名已存在");
            }
            throw ex;
        }
    }

    /** 沿异常链找 PG 的唯一约束冲突（SQLState 23xxx）。 */
    private static boolean isUniqueViolation(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sqlEx && sqlEx.getSQLState() != null
                    && sqlEx.getSQLState().startsWith("23")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String roleName(User user) {
        return user.getRole() == null ? null : user.getRole().name();
    }

    private User findByPhone(String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    private User findByUserId(String userId) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUserId, userId));
    }

    private User findByAccount(String account) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, account)
                .or()
                .eq(User::getUsername, account));
    }
}

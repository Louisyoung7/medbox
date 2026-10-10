package com.medbox.server.common.security;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;

/**
 * 当前登录用户上下文（ThreadLocal，见文档 02 的 1.3 校验顺序第 ① 步）。
 *
 * <p>由 {@code AuthInterceptor} 在鉴权通过后写入，**必须在请求结束时 {@link #clear()}**（拦截器
 * {@code afterCompletion} 已做），否则线程池复用会把上一个用户的身份带进下一次请求。
 */
public final class AuthContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    /**
     * 当前登录用户。
     *
     * @param userId 业务 user_id
     * @param role   ELDER / GUARDIAN（字符串，避免 common 层依赖 domain 枚举）
     */
    public record CurrentUser(String userId, String role) {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /** 当前用户；未登录 / 非 Web 线程（如定时任务、MQTT 消费）为 {@code null}。 */
    public static CurrentUser current() {
        return HOLDER.get();
    }

    /** 当前用户 ID；未登录为 {@code null}。 */
    public static String currentUserId() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    /** 必须已登录；未登录抛 40101（{@code feat/backend-authz} 的 {@code AccessService} 用它做第 ① 步）。 */
    public static CurrentUser required() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}

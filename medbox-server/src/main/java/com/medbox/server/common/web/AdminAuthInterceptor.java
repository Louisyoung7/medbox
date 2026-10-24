package com.medbox.server.common.web;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理接口角色守卫：独立于老人中心的 {@code AccessService}（见文档 02 第 10 章）。
 *
 * <p>{@code AuthInterceptor} 已做「是否登录」第①步并写入 {@code AuthContext}
 * （本拦截器只作用在 {@code /api/v1/admin/**}，该前缀已被 {@code AuthInterceptor} 覆盖），本类只做
 * **第②步「是否为 ADMIN」**：非 {@code ADMIN} 一律 {@code 40301}，与监护关系 / 资源归属校验彻底解耦，避免污染
 * elder 中心的权限矩阵。
 *
 * <p>注册顺序必须排在 {@code AuthInterceptor} 之后（见 {@code WebMvcConfig}），否则 {@code AuthContext} 为空会误报。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // AuthInterceptor 已保证已登录（前缀已被其覆盖）；这里再判角色。
        AuthContext.CurrentUser current = AuthContext.required();
        if (!"ADMIN".equals(current.role())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "仅管理员可执行该操作");
        }
        return true;
    }
}

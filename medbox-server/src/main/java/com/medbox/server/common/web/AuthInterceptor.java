package com.medbox.server.common.web;

import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.JwtPayload;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录校验拦截器：只做校验顺序的**第 ① 步「是否登录」**（见文档 02 的 1.3）。
 *
 * <p>监护关系 / 关系是否生效 / 角色权限（40301 / 40302）由 {@code feat/backend-authz} 的
 * {@code AccessService.require(elderId, Permission)} 承接；需要业务接口显式调用 —— 拦截器只能拿到
 * 请求本身，解析不出"目标资源归属哪位老人"。
 *
 * <p><b>为什么用拦截器而不是 Servlet Filter</b>：拦截器跑在 DispatcherServlet 内，抛出的
 * {@code BizException} 能被 {@code GlobalExceptionHandler} 接住，天然输出 {@code 40101} 的统一响应；
 * Filter 在链外，得自己拼 JSON，会复制一套错误处理。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;

    public AuthInterceptor(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        BizAssert.unauthorizedIf(header == null || !header.startsWith(BEARER_PREFIX), "缺少登录凭证");
        String token = header.substring(BEARER_PREFIX.length()).trim();
        JwtPayload payload = jwtProvider.parse(token, TokenType.ACCESS);
        AuthContext.set(new AuthContext.CurrentUser(payload.userId(), payload.role()));
        return true;
    }

    /** 请求结束必须清理，否则线程复用会把上一个用户的身份带进下一次请求。 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }
}

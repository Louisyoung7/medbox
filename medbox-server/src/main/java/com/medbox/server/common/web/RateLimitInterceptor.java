package com.medbox.server.common.web;

import com.medbox.server.common.ratelimit.RateLimitPolicy;
import com.medbox.server.common.ratelimit.RateLimitService;
import com.medbox.server.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 限流拦截器（feat/ai-limit）：在 {@code preHandle} 中按「方法 + 路径」匹配策略、取 key、调用
 * {@link RateLimitService#tryAcquire}；超限抛出的 {@code 42901} 由 {@code GlobalExceptionHandler} 兜底。
 *
 * <p>依赖仅 {@link RateLimitProperties}（配置属性 bean，在 {@code @WebMvcTest} 等切片测试里也存在），
 * 计数逻辑委托给一个非 Spring 组件的 {@link RateLimitService} 实例。
 *
 * <p>本类<b>不是</b> {@code @Component}：它只在 {@code WebMvcConfig} 里被 {@code new} 出来并注册，
 * 以「有 {@code WebMvcConfig} 才有拦截器」的方式接入。这样 {@code @WebMvcTest} 切片测试若未加载
 * {@code WebMvcConfig}（如 {@code GlobalExceptionHandlerTest}）就不会因缺少 {@code RateLimitProperties} bean 而报错。
 *
 * <p>注册时必须排在 {@code AuthInterceptor} 之后——AI 洞察维度依赖其写入的 {@code AuthContext} 取 userId。
 * 对未命中任何策略的请求是纯 no-op（直接放行），因此可安全地覆盖整个 {@code /api/v1/**}。
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;

    public RateLimitInterceptor(RateLimitProperties props) {
        this.rateLimitService = new RateLimitService(props);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            if (policy.matches(request)) {
                rateLimitService.tryAcquire(policy, policy.resolveKey(request));
                break;
            }
        }
        return true;
    }
}

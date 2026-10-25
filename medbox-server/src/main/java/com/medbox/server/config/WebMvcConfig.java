package com.medbox.server.config;

import com.medbox.server.common.web.AdminAuthInterceptor;
import com.medbox.server.common.web.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册登录校验拦截器（{@code feat/backend-auth}）。
 *
 * <p>只拦 {@code /api/v1/**}（context-path {@code /medbox} 之外的业务接口），放行：
 * <ul>
 *   <li>{@code /api/v1/auth/**} —— 注册 / 登录 / 刷新本身就是为了拿 token；</li>
 *   <li>{@code /actuator/**} —— 健康检查，Spring 原生格式，不套统一响应；</li>
 *   <li>{@code /api/v1/_debug/seed}、{@code /api/v1/_debug/mqtt/**} 与 {@code /api/v1/_debug/plan-reminder/trigger}
 *        —— {@code DevtoolsController} 的联调辅助端点（feat/backend-devtools）。它们**不要求登录**，因为端点本身由
 *        {@code medbox.devtools.enabled} 门禁控制：该属性为 false 时 {@code DevtoolsController} 根本不装配
 *        （访问即 40401），生产零暴露面；开启即代表「本地开发模式」，再加上登录纯属冗余摩擦。</li>
 *   <li><b>注意</b>：同前缀的 {@code WsDebugController}（{@code /api/v1/_debug/ws/**}）调试推送端点<b>必须登录</b>——
 *        它要经 {@code AccessService.require(elderId, READ_ALARM)} 做归属校验（越权照样 40301），而该校验依赖
 *        {@code AuthInterceptor} 写入的 {@code AuthContext}。因此不能把整个 {@code /api/v1/_debug/**} 一刀切免登录，
 *        否则 {@code AuthContext} 恒为空、所有带 token 的请求都会误报 401。Devtools 与 Ws 的免登录策略刻意不同。</li>
 * </ul>
 *
 * <p>后续分支新增**无需登录**的接口（如抓拍上传走设备侧 HMAC 签名）时，在这里加排除路径。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor, AdminAuthInterceptor adminAuthInterceptor) {
        this.authInterceptor = authInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                // 登录类接口与 actuator 永远放行；DevtoolsController 的 seed / mqtt 模拟上报按设计免登录
                // （由 medbox.devtools.enabled 门禁控制），单独放行。同前缀的 WsDebugController 调试推送端点
                // 需要登录 + 归属校验（AccessService.require 依赖 AuthInterceptor 写入的 AuthContext），
                // 不能被一刀切排除，否则 AuthContext 为空会恒返回 401。
                .excludePathPatterns("/api/v1/auth/**",
                        "/api/v1/_debug/seed", "/api/v1/_debug/mqtt/**",
                        "/api/v1/_debug/plan-reminder/trigger",
                        "/api/v1/devices/*/captures",   // 抓拍上传走设备 HMAC，不走用户 JWT（feat/capture-core）
                        "/actuator/**");

        // 管理接口独立角色守卫：必须排在 AuthInterceptor 之后（前缀已被其覆盖、AuthContext 已写入，
        // 否则拦截器按顺序执行会先跑本守卫、拿到空 AuthContext 误报 401）。非 ADMIN → 40301。
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/v1/admin/**");
    }
}

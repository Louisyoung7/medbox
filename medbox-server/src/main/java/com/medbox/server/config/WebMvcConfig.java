package com.medbox.server.config;

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
 *   <li>{@code /actuator/**} —— 健康检查，Spring 原生格式，不套统一响应。</li>
 * </ul>
 *
 * <p>后续分支新增**无需登录**的接口（如抓拍上传走设备侧 HMAC 签名）时，在这里加排除路径。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/**", "/actuator/**");
    }
}

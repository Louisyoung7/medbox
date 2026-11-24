package com.medbox.server.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * APK 静态下载目录映射（{@code feat/backend-app-support}，见文档 02j / 08）。
 *
 * <p>把 {@code medbox.app.download-dir} 映射到 {@code /app/**}（含 context-path 即
 * {@code /medbox/app/**}）。该路径不在 {@code /api/v1/**} 下，因此不经过
 * {@code AuthInterceptor}，APK 可直接下载；{@code spring.web.resources.add-mappings=false}
 * 只关闭默认静态映射，不影响这里显式注册的 handler。
 *
 * <p>目录不存在时请求返回 40401 / 404，不影响应用启动（发版前目录可以还没建）。
 */
@Configuration
@EnableConfigurationProperties(AppVersionProperties.class)
public class AppVersionConfig implements WebMvcConfigurer {

    private final AppVersionProperties properties;

    public AppVersionConfig(AppVersionProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(properties.getDownloadDir()).toAbsolutePath().normalize();
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/app/**").addResourceLocations(location);
    }
}

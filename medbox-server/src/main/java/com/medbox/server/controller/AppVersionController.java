package com.medbox.server.controller;

import com.medbox.server.dto.R;
import com.medbox.server.dto.app.AppVersionResponse;
import com.medbox.server.service.AppVersionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端版本检查（{@code feat/backend-app-support}，见文档 02j）。
 *
 * <p>免登录：APK 首次启动、尚未登录时就要能查最新版本。对应静态 APK 下载
 * {@code GET /medbox/app/{apkFileName}} 同样不走 JWT（见 {@code WebMvcConfig} 的排除路径）。
 */
@RestController
@RequestMapping("/api/v1/app")
public class AppVersionController {

    private final AppVersionService appVersionService;

    public AppVersionController(AppVersionService appVersionService) {
        this.appVersionService = appVersionService;
    }

    /** GET /app/version?versionCode=100：返回最新版本、下载地址与是否已是最新。 */
    @GetMapping("/version")
    public R<AppVersionResponse> version(
            @RequestParam(value = "versionCode", required = false) Integer versionCode) {
        return R.ok(appVersionService.getVersion(versionCode));
    }
}

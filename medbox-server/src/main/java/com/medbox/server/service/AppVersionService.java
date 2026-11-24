package com.medbox.server.service;

import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.config.AppVersionProperties;
import com.medbox.server.dto.app.AppVersionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 客户端版本检查（{@code feat/backend-app-support}，见文档 02j）。
 *
 * <p>版本信息来自 {@code medbox.app.*} 配置，不查库。下载地址优先用
 * {@code medbox.app.download-url}（对象存储 / CDN）；留空时按后端对外地址推导，
 * 指向 {@code /medbox/app/{apkFileName}}（由 {@link com.medbox.server.config.AppVersionConfig} 映射本地目录）。
 */
@Service
public class AppVersionService {

    private final AppVersionProperties properties;
    private final String medboxHost;
    private final int httpPort;

    public AppVersionService(AppVersionProperties properties,
                             @Value("${medbox.host:192.168.1.10}") String medboxHost,
                             @Value("${medbox.ports.http:8080}") int httpPort) {
        this.properties = properties;
        this.medboxHost = medboxHost;
        this.httpPort = httpPort;
    }

    /**
     * @param clientVersionCode 客户端当前 versionCode；未传（App 启动即查）时只返回最新版信息
     */
    public AppVersionResponse getVersion(Integer clientVersionCode) {
        BizAssert.badRequestIf(clientVersionCode != null && clientVersionCode < 0,
                "versionCode 不能为负数");
        int latest = properties.getVersionCode();
        boolean upToDate = clientVersionCode != null && clientVersionCode >= latest;
        return new AppVersionResponse(properties.getVersionName(), latest, resolveDownloadUrl(),
                properties.getReleaseNotes(), upToDate);
    }

    private String resolveDownloadUrl() {
        if (StringUtils.hasText(properties.getDownloadUrl())) {
            return properties.getDownloadUrl();
        }
        return "http://" + medboxHost + ":" + httpPort + "/medbox/app/" + apkFileName();
    }

    private String apkFileName() {
        return StringUtils.hasText(properties.getApkFileName())
                ? properties.getApkFileName()
                : "medbox-" + properties.getVersionName() + ".apk";
    }
}

package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.medbox.server.config.AppVersionProperties;
import com.medbox.server.dto.app.AppVersionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link AppVersionService} 的下载地址解析（配置覆盖 / 文件名推导）单元测试。 */
class AppVersionServiceTest {

    @Test
    @DisplayName("配置了 download-url → 原样返回（对象存储 / CDN 场景）")
    void 下载地址覆盖() {
        AppVersionProperties properties = new AppVersionProperties();
        properties.setDownloadUrl("https://cdn.example.com/medbox/app.apk");
        AppVersionService service = new AppVersionService(properties, "192.168.1.10", 8080);

        AppVersionResponse response = service.getVersion(100);

        assertThat(response.downloadUrl()).isEqualTo("https://cdn.example.com/medbox/app.apk");
    }

    @Test
    @DisplayName("apk-file-name 留空 → 按 medbox-{version-name}.apk 推导")
    void 文件名按版本名推导() {
        AppVersionProperties properties = new AppVersionProperties();
        properties.setVersionName("1.2.3");
        properties.setVersionCode(123);
        AppVersionService service = new AppVersionService(properties, "192.168.1.10", 8080);

        AppVersionResponse response = service.getVersion(100);

        assertThat(response.downloadUrl())
                .isEqualTo("http://192.168.1.10:8080/medbox/app/medbox-1.2.3.apk");
        assertThat(response.upToDate()).isFalse();
    }
}

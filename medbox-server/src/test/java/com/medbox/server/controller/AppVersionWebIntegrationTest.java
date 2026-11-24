package com.medbox.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/app/version} 与 APK 静态下载的集成测试（{@code feat/backend-app-support}，见文档 02j）。
 *
 * <p>真实链路：MockMvc → {@code AppVersionController} → 真实 {@code AppVersionService} → 配置；
 * 以及 {@code AppVersionConfig} 注册的 {@code /app/**} 静态资源映射。覆盖免登录、版本比对、
 * 下载地址推导（配置覆盖由单元测试覆盖）与参数边界。
 *
 * <p><b>不连真实 PostgreSQL</b>：用内嵌 H2 替换 DataSource；本接口不查库。
 */
@SpringBootTest(properties = {
        "medbox.host=192.168.1.77",
        "medbox.ports.http=18080",
        "medbox.app.version-name=2.0.0",
        "medbox.app.version-code=200",
        "medbox.app.apk-file-name=medbox-2.0.0.apk",
        "medbox.app.release-notes=P3 测试版本",
        "medbox.app.download-url=",
        "medbox.app.download-dir=build/test-app-download"
})
@AutoConfigureMockMvc
class AppVersionWebIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-app-version-test")
                    .build();
        }
    }

    private static final String APK_NAME = "medbox-2.0.0.apk";
    private static final byte[] APK_BYTES = {0x50, 0x4b, 0x03, 0x04, 0x00};

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void prepareApk() throws IOException {
        Path dir = Paths.get("build/test-app-download");
        Files.createDirectories(dir);
        Files.write(dir.resolve(APK_NAME), APK_BYTES);
    }

    @Test
    @DisplayName("免登录查版本 → 200，返回最新版本与推导的下载地址")
    void 免登录查最新版本() throws Exception {
        mockMvc.perform(get("/api/v1/app/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.latestVersionName").value("2.0.0"))
                .andExpect(jsonPath("$.data.latestVersionCode").value(200))
                .andExpect(jsonPath("$.data.downloadUrl")
                        .value("http://192.168.1.77:18080/medbox/app/" + APK_NAME))
                .andExpect(jsonPath("$.data.releaseNotes").value("P3 测试版本"))
                // 未传客户端版本时无法判定是否最新
                .andExpect(jsonPath("$.data.upToDate").value(false));
    }

    @Test
    @DisplayName("客户端 versionCode 落后 → upToDate=false")
    void 旧版本可升级() throws Exception {
        mockMvc.perform(get("/api/v1/app/version").param("versionCode", "199"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.upToDate").value(false));
    }

    @Test
    @DisplayName("客户端 versionCode 相同或更高 → upToDate=true")
    void 已是最新版本() throws Exception {
        mockMvc.perform(get("/api/v1/app/version").param("versionCode", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.upToDate").value(true));

        mockMvc.perform(get("/api/v1/app/version").param("versionCode", "201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.upToDate").value(true));
    }

    @Test
    @DisplayName("versionCode 为负数 → 40001")
    void 非法版本号返回_40001() throws Exception {
        mockMvc.perform(get("/api/v1/app/version").param("versionCode", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("APK 静态下载：/app/{fileName} 免登录返回文件内容")
    void 静态下载APK() throws Exception {
        mockMvc.perform(get("/app/{fileName}", APK_NAME))
                .andExpect(status().isOk())
                .andExpect(content().bytes(APK_BYTES));
    }
}

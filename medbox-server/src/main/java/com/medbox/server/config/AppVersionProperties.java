package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 客户端版本检查与 APK 分发配置（{@code medbox.app.*}，见文档 02j / 08）。
 *
 * <p>版本信息全部走配置、不落库：发版时改 {@code version-name} / {@code version-code}，
 * 把 APK 放进 {@code download-dir}（或改用对象存储并填 {@code download-url}）即可。
 */
@ConfigurationProperties(prefix = "medbox.app")
public class AppVersionProperties {

    /** 最新版本名（展示用，如 {@code 1.0.0}）。 */
    private String versionName = "1.0.0";

    /** 最新 versionCode（与 Android manifest 的 versionCode 同口径，整数递增）。 */
    private int versionCode = 100;

    /** APK 文件名；留空时按 {@code medbox-{version-name}.apk} 推导。 */
    private String apkFileName;

    /** 下载地址完整覆盖；留空时按 {@code http://{medbox.host}:{medbox.ports.http}/medbox/app/{apkFileName}} 推导。 */
    private String downloadUrl;

    /** 更新说明；可为空。 */
    private String releaseNotes;

    /** APK 本地静态目录，映射到 {@code /app/**}；需可读。 */
    private String downloadDir = "/var/medbox/apk";

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public int getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(int versionCode) {
        this.versionCode = versionCode;
    }

    public String getApkFileName() {
        return apkFileName;
    }

    public void setApkFileName(String apkFileName) {
        this.apkFileName = apkFileName;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getReleaseNotes() {
        return releaseNotes;
    }

    public void setReleaseNotes(String releaseNotes) {
        this.releaseNotes = releaseNotes;
    }

    public String getDownloadDir() {
        return downloadDir;
    }

    public void setDownloadDir(String downloadDir) {
        this.downloadDir = downloadDir;
    }
}

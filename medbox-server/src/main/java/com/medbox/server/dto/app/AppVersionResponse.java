package com.medbox.server.dto.app;

/**
 * 客户端版本检查结果，对应 {@code GET /app/version}（见文档 02j）。
 *
 * @param latestVersionName 最新版本名（展示用）
 * @param latestVersionCode 最新 versionCode（Android manifest 同口径）
 * @param downloadUrl       APK 下载地址（配置覆盖或按后端地址推导）
 * @param releaseNotes      更新说明，未配置时为 null
 * @param upToDate          客户端 versionCode 是否已不低于最新；未传客户端版本时为 false
 */
public record AppVersionResponse(
        String latestVersionName,
        int latestVersionCode,
        String downloadUrl,
        String releaseNotes,
        boolean upToDate) {
}

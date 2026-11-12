package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 抓拍图片相关配置（{@code medbox.capture.*}，见文档 02b §1.1 / 06 §2.12）。
 *
 * <p>与 {@code DeviceProperties} 同一套路：能配的都进配置，Service 不写死常量。
 * 密钥类字段走环境变量覆盖（{@code MEDBOX_CAPTURE_UPLOAD_SECRET} / {@code MEDBOX_CAPTURE_FILE_TOKEN_SECRET}），
 * 不入库、不进真实账号体系。设备上报已不做人脸识别、不报置信度，故本类不再有置信度阈值。
 */
@ConfigurationProperties(prefix = "medbox.capture")
public class CaptureProperties {

    /** 抓拍图片本地存储根目录；按 {@code YYYY-MM-DD} 分子目录落盘。 */
    private String dir = "/var/medbox/captures";

    /** 未读最长保留天数（unread）：未读 expire_at = captured_at + 该值（默认 30）。 */
    private int retentionDaysUnread = 30;

    /** 已读后保留天数（read）：首次查看后 expire_at = MIN(captured_at+unread, viewed_at+该值)（默认 7）。 */
    private int retentionDaysRead = 7;

    /** 设备上传 HMAC 共用密钥（一把密钥写在固件与后端配置里，不入库，见 02b §1.1）。 */
    private String uploadSecret;

    /** fileToken 签发/校验密钥（小程序 {@code <image src>} 用，避免暴露长期 JWT）。 */
    private String fileTokenSecret;

    /** fileToken 有效期（分钟），默认 5。 */
    private int fileTokenTtlMinutes = 5;

    /** 过期清理定时任务总开关（默认 true）。 */
    private boolean cleanupEnabled = true;

    /** 过期清理 Cron 表达式（默认每日 04:00，6 段式）。 */
    private String cleanupCron = "0 0 4 * * *";

    /** 每轮过期删除的批量上限（默认 500，防长事务锁表）。 */
    private int cleanupBatchSize = 500;

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public int getRetentionDaysUnread() {
        return retentionDaysUnread;
    }

    public void setRetentionDaysUnread(int retentionDaysUnread) {
        this.retentionDaysUnread = retentionDaysUnread;
    }

    public int getRetentionDaysRead() {
        return retentionDaysRead;
    }

    public void setRetentionDaysRead(int retentionDaysRead) {
        this.retentionDaysRead = retentionDaysRead;
    }

    public String getUploadSecret() {
        return uploadSecret;
    }

    public void setUploadSecret(String uploadSecret) {
        this.uploadSecret = uploadSecret;
    }

    public String getFileTokenSecret() {
        return fileTokenSecret;
    }

    public void setFileTokenSecret(String fileTokenSecret) {
        this.fileTokenSecret = fileTokenSecret;
    }

    public int getFileTokenTtlMinutes() {
        return fileTokenTtlMinutes;
    }

    public void setFileTokenTtlMinutes(int fileTokenTtlMinutes) {
        this.fileTokenTtlMinutes = fileTokenTtlMinutes;
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public void setCleanupEnabled(boolean cleanupEnabled) {
        this.cleanupEnabled = cleanupEnabled;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }
}

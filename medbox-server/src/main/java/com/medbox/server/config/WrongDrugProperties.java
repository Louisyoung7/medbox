package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 错服判定与 WRONG_DRUG 告警配置（{@code medbox.alarm.wrong-drug.*}，见文档 06 的 2.9）。
 *
 * <p>与 {@code medbox.alarm.miss.*} / {@code medbox.device.*} 同一风格：可改的都进配置。
 * 逃生开关 {@code medbox.alarm.wrong-drug.enabled} 默认 true；CI / 排障可置 false 关掉整个判定。
 */
@ConfigurationProperties(prefix = "medbox.alarm.wrong-drug")
public class WrongDrugProperties {

    /** 是否启用错服判定；false 时整个判定 Bean 不工作（CI / 排障用）。 */
    private boolean enabled = true;

    /**
     * 摄像头识别置信度阈值：{@code confidence < threshold} 时不做错服判定，
     * 仅将抓拍 {@code capture.reason} 标记为 LOW_CONFIDENCE 交人工复核。默认 0.6。
     */
    private double confidenceThreshold = 0.6;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }
}

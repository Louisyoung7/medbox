package com.medbox.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流配置（medbox.ratelimit.*，见 02 的 1.4 与 07 的 feat/ai-limit）。
 *
 * <p>三个维度的 {@code enabled/limit/window-seconds} 均可覆盖；未配置时回落到
 * {@link com.medbox.server.common.ratelimit.RateLimitPolicy} 里的默认值（limit/window-seconds 为 0 即"用默认"）。
 * 顶层 {@code enabled=false} 时整体跳过限流（逃生开关）。
 */
@ConfigurationProperties(prefix = "medbox.ratelimit")
public class RateLimitProperties {

    private boolean enabled = true;

    private RateLimitPolicyConfig login = new RateLimitPolicyConfig();
    private RateLimitPolicyConfig aiInsight = new RateLimitPolicyConfig();
    private RateLimitPolicyConfig deviceCommand = new RateLimitPolicyConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public RateLimitPolicyConfig getLogin() {
        return login;
    }

    public void setLogin(RateLimitPolicyConfig login) {
        this.login = login;
    }

    public RateLimitPolicyConfig getAiInsight() {
        return aiInsight;
    }

    public void setAiInsight(RateLimitPolicyConfig aiInsight) {
        this.aiInsight = aiInsight;
    }

    public RateLimitPolicyConfig getDeviceCommand() {
        return deviceCommand;
    }

    public void setDeviceCommand(RateLimitPolicyConfig deviceCommand) {
        this.deviceCommand = deviceCommand;
    }

    /** 按维度名取该维度的覆盖配置；未知维度返回 null（视为禁用）。 */
    public RateLimitPolicyConfig forPolicy(com.medbox.server.common.ratelimit.RateLimitPolicy policy) {
        return switch (policy.dimension()) {
            case "auth-login" -> login;
            case "ai-insight" -> aiInsight;
            case "device-command" -> deviceCommand;
            default -> null;
        };
    }

    /** 单维度配置：未填 limit / window-seconds 时以 0 表示"用策略默认值"。 */
    public static class RateLimitPolicyConfig {
        private boolean enabled = true;
        private int limit;
        private int windowSeconds;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }
}

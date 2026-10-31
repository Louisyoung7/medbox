package com.medbox.server.ai;

/**
 * 调用大模型所需的一份凭据（每老人一份，见 05 §2.1 BYOK）。
 *
 * <p>只在内存中存在：由 {@code LlmConfigService} 从 {@code llm_config} 解密组装，
 * 用完即弃，不落库、不进日志（尤其 {@code apiKey} 绝不打日志）。
 */
public record LlmCredentials(String baseUrl, String apiKey, String model) {
}
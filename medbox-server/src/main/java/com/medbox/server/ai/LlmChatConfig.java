package com.medbox.server.ai;

/**
 * BYOK Chat 配置（refactor/ai-insight-layer）。
 *
 * <p>由调用方从 {@code llm_config} 按 {@code elderId} 查出后构造，供
 * {@link AiConfig#createChatClient(LlmChatConfig)} 动态构造 ChatClient 使用。后端不托管大模型，
 * 仅透传监护人代配的端点 / 密钥 / 模型名。
 *
 * @param baseUrl 模型端点（含本机 / 局域网自建 Ollama，如 {@code http://localhost:11434}）
 * @param apiKey  监护人代配的 Key（本机端点可为占位值）
 * @param model   模型名（如 {@code gpt-4o-mini}）
 */
public record LlmChatConfig(String baseUrl, String apiKey, String model) {
}

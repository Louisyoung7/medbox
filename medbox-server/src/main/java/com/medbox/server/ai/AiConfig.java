package com.medbox.server.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Configuration;

/**
 * AI 洞察层 Chat 客户端工厂（refactor/ai-insight-layer 清理后保留）。
 *
 * <p>BYOK 模式：后端不托管大模型，每老人一份 {@code llm_config}（provider / baseUrl / apiKey / model），
 * 由调用方按 {@code elderId} 查出后构造 {@link LlmChatConfig} 传入 {@link #createChatClient} 动态构造
 * {@link ChatClient}。<b>不注入全局单例</b>：全局 chat 自动装配已在 application.yml 由
 * {@code spring.ai.model.chat=none} 禁用，缺全局 Key 不会阻塞启动。
 *
 * <p>端点不限定公有云：{@code baseUrl} 也支持本机 / 局域网自建 Ollama（如 {@code http://localhost:11434}），
 * 统一走 openai-compatible 协议（Spring AI 2.x 基于官方 OpenAI Java SDK）。
 */
@Configuration
public class AiConfig {

    /**
     * 按给定的非对称配置动态构造 ChatClient（openai-compatible）。
     *
     * @param config 调用方从 {@code llm_config} 按老人查出的 baseUrl / apiKey / model
     * @return 该老人专属的 ChatClient（每次调用新建，无全局状态）
     */
    public ChatClient createChatClient(LlmChatConfig config) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .model(config.model())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(options)
                .build();
        return ChatClient.create(chatModel);
    }
}

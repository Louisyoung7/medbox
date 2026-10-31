package com.medbox.server.ai;

/** 大模型调用出口：唯一一处 new ChatClient。测试可整层打桩，不连外网。 */
public interface LlmChatGateway {
    String complete(LlmCredentials credentials, String systemPrompt, String userPrompt);
    reactor.core.publisher.Flux<String> stream(LlmCredentials credentials, String systemPrompt, String userPrompt);
}
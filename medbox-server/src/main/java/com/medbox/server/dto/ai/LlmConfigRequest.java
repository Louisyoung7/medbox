package com.medbox.server.dto.ai;


/** LLM 配置请求。 */
public record LlmConfigRequest(String provider, String apiKey, String model, String baseUrl, Boolean enabled) {}
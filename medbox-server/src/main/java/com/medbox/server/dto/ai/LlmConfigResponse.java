package com.medbox.server.dto.ai;


/** LLM 配置响应。 */
public record LlmConfigResponse(String elderId, String provider, String model, String baseUrl,
                                String apiKeyMask, boolean enabled,
                                boolean insightEnabled, String insightCadence) {}
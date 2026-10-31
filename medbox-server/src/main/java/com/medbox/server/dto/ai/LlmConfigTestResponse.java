package com.medbox.server.dto.ai;



/** LLM 配置测试响应。 */
public record LlmConfigTestResponse(boolean ok, long latencyMs, String message) {}
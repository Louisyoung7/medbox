package com.medbox.server.ws.payload;

import java.util.List;

/** AI 意见。 */
public record AiInsightPayload(String insightId, String elderId, String type,
                               String content, List<String> citations, String disclaimer) {
}
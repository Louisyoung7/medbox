package com.medbox.server.dto.ai;

import java.util.List;

/** 洞察响应。 */
public record InsightResponse(String elderId, String type, InsightRange range,
                              String content, List<String> citations, String disclaimer) {}
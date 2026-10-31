package com.medbox.server.dto.ai;

import com.medbox.server.dto.ai.InsightRange;

public record InsightRequest(String elderId, String type, InsightRange range, String deviceId) {}
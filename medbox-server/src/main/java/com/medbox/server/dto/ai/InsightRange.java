package com.medbox.server.dto.ai;

import java.time.OffsetDateTime;

/** 洞察时间范围。 */
public record InsightRange(OffsetDateTime from, OffsetDateTime to) {}
package com.medbox.server.dto.ai;


/** 洞察计划请求。 */
 public record InsightScheduleRequest(Boolean enabled, String cadence) {}
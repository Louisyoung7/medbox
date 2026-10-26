package com.medbox.server.dto.env;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * {@code GET /env/realtime} 的响应体（见文档 02 第 9 章）。
 *
 * <p>合并药箱下「最新非空温度/湿度行」（温湿度传感器）与「最新非空光照行」（光照传感器）的读数；
 * 任一传感器无数据则对应字段为 {@code null}。{@code sampleTime} 取两者较新者。
 */
public record EnvRealtimeResponse(BigDecimal temperature,
                                  BigDecimal humidity,
                                  BigDecimal lux,
                                  OffsetDateTime sampleTime) {
}

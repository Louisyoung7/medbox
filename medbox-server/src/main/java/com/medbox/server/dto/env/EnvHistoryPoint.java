package com.medbox.server.dto.env;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 历史曲线上的一个点（{@code GET /env/history}，见文档 02 第 9 章）。
 *
 * <p>温湿度与光照来自不同传感器、上报时刻可能不同：原始模式下一行只带一种传感器的数值，
 * 其余为 {@code null}；分桶模式下按桶 {@code avg()} 聚合（{@code avg} 天然忽略 {@code null}，
 * 故同一桶内温湿度来自 TH 行、光照来自 LIGHT 行可正确合并）。
 */
public record EnvHistoryPoint(OffsetDateTime sampleTime,
                              BigDecimal temperature,
                              BigDecimal humidity,
                              BigDecimal lux) {
}

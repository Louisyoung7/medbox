package com.medbox.server.dto.env;

import java.util.List;

/**
 * {@code GET /env/history} 的响应体（见文档 02 第 9 章）。
 *
 * <p>{@code points} 按时间有序；原始模式为逐条样本，分桶模式为每桶聚合点。
 */
public record EnvHistoryResponse(List<EnvHistoryPoint> points) {
}

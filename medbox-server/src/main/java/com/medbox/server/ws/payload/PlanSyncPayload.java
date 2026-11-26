package com.medbox.server.ws.payload;

/**
 * 服药计划同步状态帧载荷（{@code PLAN_SYNC}，见文档 04 §3 / 02d）。
 *
 * <p>设备 {@code up/ack} 结算后 {@code med_plan.sync_state} 落库时推送，
 * 客户端按 {@code planId} 局部更新列表项的 {@code syncState}，不整页重拉。
 * 手动同步 {@code POST /plans/{planId}/sync} 走同一回执链路，故同样会推。
 *
 * @param planId    计划业务 ID
 * @param elderId   归属老人业务 ID（推送主题 = elder:{elderId}）
 * @param syncState 终态 {@code SYNCED_TO_DEVICE} / {@code SYNC_FAILED}
 */
public record PlanSyncPayload(String planId, String elderId, String syncState) {
}

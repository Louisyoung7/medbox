/**
 * 报告域接口（`feat/mp-report`，对应 medbox-spec/02 第 7 章 §7.0 `GET /adherence` 与 `GET /records/export`）。
 *
 * <p>后端 `record-report` 已落地（`07` 清单 `[x]`），接口形状以 02 第 7 章为准：
 * - `GET /adherence`：统一响应 `R<AdherenceResponse>`（按时率/漏服等统计，全角色可读，老人仅本人）；
 * - `GET /records/export`：**CSV 文件流**，不走 `R<T>` 包装，权限 `EXPORT_RECORD`（仅监护人）。
 *
 * <p>文件流走 `request.downloadFile`（非 `get`），否则拿到的是字符串而非文件。
 * 所有接口需鉴权（默认 `auth: true`），错误码走 02 的 1.2 封闭集合：
 * 40101（登录失效，请求层自动续期）/ 40001（参数非法）/ 40301（老人写 / 越权）/ 40302（监护 PENDING）。
 */

import { get, downloadFile } from '@/api/request.js'

/**
 * 依从性统计。
 *
 * @param {Object} params
 * @param {string} params.elderId 老人业务 ID（监护人传选中老人，老人端传自身）
 * @param {'day'|'week'|'month'} params.period 滚动窗口周期；非法 → 40001
 * @param {Object} [options] 透传给 `@/api/request` 的 `silent` 等
 * @returns {Promise<Object>} `AdherenceResponse`（见 `utils/report.parseAdherence`）
 * @throws {ApiError} 40101 / 40001 / 40301 / 40302 / -1
 */
export function getAdherence(params, options) {
  return get('/adherence', params, options)
}

/**
 * 导出服药记录 CSV（文件流）。
 *
 * <p>底层走 `downloadFile`：注入 `Authorization: Bearer`、解析 `Content-Disposition` 文件名，
 * 兑现 `{ tempFilePath, filePath, filename }` 由页面保存 / 转发。非 2xx（40301/40302/40001）由
 * `downloadFile` 按 JSON 错误体或状态码 reject {@link ApiError}。不触发 401 续期重试。
 *
 * @param {Object} params
 * @param {string} params.elderId 老人业务 ID
 * @param {string} [params.from] 起始（ISO-8601；空=默认最近 30 天起点）
 * @param {string} [params.to] 结束（ISO-8601；空=now）
 * @param {Object} [options] 透传 `{ filePath }` 等给 `downloadFile`
 * @returns {Promise<{tempFilePath: string, filePath: string, filename: string|null}>}
 * @throws {ApiError} 40101 / 40001 / 40301 / 40302 / -1
 */
export function exportRecords(params, options) {
  return downloadFile({ url: '/records/export', params, ...(options || {}) })
}

/**
 * 抓拍域接口（`feat/mp-capture-event`，对应 medbox-spec/02 设备管理.md §4.1）。
 *
 * <p>本分支只用到"读元数据取 fileToken"这一端点（`GET /captures/{captureId}`），
 * 原图二进制走 `GET /captures/{captureId}/file?token=`（由页面用 `buildCaptureFileUrl`
 * 拼 `<image src>`，不经过本封装）。抓拍列表 / 删除 / 上传归独立分支 `feat/mp-capture`。
 *
 * <p>所有接口需鉴权（默认 `auth: true`）；错误码走 02 的 1.2 封闭集合：
 * 40101（登录失效，请求层自动续期）/ 40301（越权）/ 40302（监护关系 PENDING）/
 * 40401（抓拍不存在）/ -1（网络）。
 */

import { get, del } from '@/api/request.js'

/**
 * 抓拍详情（元数据，含 5 分钟短期 `fileToken`）。
 *
 * <p>返回原始 `CaptureResponse`，页面自行 {@link parseCapture} 取 `fileToken` 拼图片 URL。
 *
 * @param {string} captureId 抓拍编号
 * @param {Object} [options] 透传给 `@/api/request` 的 `silent` 等
 * @returns {Promise<Object>} `CaptureResponse`（`captureId` / `fileToken` / `faceResult` / `confidence` / `expireAt` / `mime` 等）
 * @throws {ApiError} 40101 / 40301 / 40302 / 40401 / -1
 */
export function getCapture(captureId, options) {
  return get(`/captures/${captureId}`, undefined, options)
}

/**
 * 抓拍列表（仅元数据，不含文件流；每条含 5 分钟短期 `fileToken`）。
 *
 * <p>对应 `02 §4.1.2` ②：按 `elderId`（必填）、`reason`、`from`/`to`、`page`/`size` 过滤；
 * 分页上限 200（图片元数据体积小）。返回原始分页响应，页面用 {@link parseCapturesPage} 解析。
 *
 * @param {string} elderId 目标老人（READ_CAPTURE：老人本人 + GUARDIAN）
 * @param {Object} [params] `{ reason, from, to, page, size }`
 * @param {Object} [options] 透传给 `@/api/request`
 * @returns {Promise<Object>} `R<PageResult<CaptureResponse>>`（`list` / `total` / `page` / `size`）
 * @throws {ApiError} 40101 / 40301（越权）/ 40302（监护关系 PENDING）/ -1（网络）
 */
export function listCaptures(elderId, params, options) {
  return get('/captures', { elderId, ...(params || {}) }, options)
}

/**
 * 删除抓拍（仅 GUARDIAN，`WRITE_CAPTURE`；老人本人 / 护理 / 医生 40301）。
 *
 * <p>对应 `02 §4.1.2` ⑥：`DELETE /captures/{captureId}` → `R<Void>`。
 *
 * @param {string} captureId 抓拍编号
 * @param {Object} [options] 透传给 `@/api/request`
 * @returns {Promise<null>}
 * @throws {ApiError} 40101 / 40301 / 40302 / 40401（不存在或已清理）/ -1
 */
export function deleteCapture(captureId, options) {
  return del(`/captures/${captureId}`, undefined, options)
}

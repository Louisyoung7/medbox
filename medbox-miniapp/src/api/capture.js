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

import { get } from '@/api/request.js'

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

/**
 * 抓拍 / 待确认态纯函数（`feat/mp-capture-event`，对应 medbox-spec/02 设备管理.md §4.1、
 * 02_服药记录与依从性.md §7.1）。
 *
 * <p>只放与页面解耦、可单测的逻辑：待确认判定、fileToken 图片 URL 拼接、抓拍元数据容错解析、
 * 确认请求体组装。后端 `backend-capture-event` 未落地，字段对 `pendingConfirm` / `faceResult`
 * / `confidence` 缺失做容错；后端定稿后仅改本文件即可收紧。
 *
 * <p>本文件为纯函数，**不引入 `@/config/index.js`**（`uni.getStorageSync` 在 Vitest 下不可用），
 * 故 {@link buildCaptureFileUrl} 的 `baseUrl` 由调用方（页面，已处于 uni 运行时）传入，
 * 默认空串返回相对路径。
 */

/**
 * 记录是否处于"待确认"态。
 *
 * <p>口径（02 §7.1）：`pendingConfirm` 是 `med_record` 派生态
 * `(confidence IS NULL OR confidence < 阈值) OR faceResult = 'UNCERTAIN'`；
 * 后端未落地时为兼容契约，前端以 `pendingConfirm === true` 或 `faceResult === 'UNCERTAIN'`
 * 任一命中即判待确认。
 *
 * @param {Object} [record]
 * @returns {boolean}
 */
export function isUncertain(record) {
  const r = record || {}
  if (r.pendingConfirm === true) return true
  return r.faceResult === 'UNCERTAIN'
}

/**
 * 拼接抓拍原图访问 URL（fileToken 短时令牌，拼 query，不暴露长期 JWT）。
 *
 * <p>`02 §4.1`：小程序 `<image src>` 无法带自定义 Header，故用后端签发的 5 分钟短期
 * `fileToken` 拼在 URL 上。调用方传入 `baseUrl`（页面用 `config/index.js` 的 `BASE_URL`），
 * 默认空串返回相对路径（便于单测）。
 *
 * @param {string} captureId 抓拍编号
 * @param {string} fileToken 短期令牌
 * @param {string} [baseUrl] 服务前缀（如 `http://HOST:8080/medbox/api/v1`）
 * @returns {string}
 */
export function buildCaptureFileUrl(captureId, fileToken, baseUrl = '') {
  const id = encodeURIComponent(captureId || '')
  const tk = encodeURIComponent(fileToken || '')
  return `${baseUrl}/captures/${id}/file?token=${tk}`
}

/**
 * 抓拍元数据容错解析（`GET /captures/{captureId}` → `CaptureResponse`，见 02 §4.1.2）。
 *
 * <p>只抽取页面需要的字段，缺省给空串 / undefined，避免空值视图崩溃。
 *
 * @param {Object|null|undefined} data 请求层兑现的 `data`
 * @returns {{ captureId: string, fileToken: string, faceResult: string, confidence: (number|null), expireAt: string, mime: string }}
 */

import { formatDateTime, parseUtc } from '@/utils/datetime.js'

export function parseCapture(data) {
  const d = data || {}
  let confidence = null
  if (typeof d.confidence === 'number') confidence = d.confidence
  else if (typeof d.confidence === 'string' && d.confidence !== '') {
    const n = Number(d.confidence)
    if (!Number.isNaN(n)) confidence = n
  }
  return {
    captureId: d.captureId || '',
    fileToken: d.fileToken || '',
    faceResult: d.faceResult || '',
    confidence,
    expireAt: d.expireAt || '',
    mime: d.mime || '',
  }
}

/**
 * 组装人工确认请求体（`POST /records/{recordId}/confirm` 的 UNCERTAIN 分支）。
 *
 * <p>02 §7.1：监护人看图后改写 `faceResult` 为 `TAKEN`（确认已服）/ `ABSENT`（确认未服），
 * `source` 恒置 `MANUAL`、`confidence` 可置空；仅非空的 `faceResult` 入参，
 * `note` 选填（去空白后空则不传）。普通补录（不带 faceResult）时 `payload` 为空对象。
 *
 * @param {{ faceResult?: string, note?: string }} [opts]
 * @returns {Object}
 */
export function buildConfirmPayload(opts) {
  const { faceResult, note } = opts || {}
  const payload = {}
  if (faceResult === 'TAKEN' || faceResult === 'ABSENT') payload.faceResult = faceResult
  const trimmed = note && String(note).trim()
  if (trimmed) payload.note = trimmed
  return payload
}

/**
 * 抓拍列表分页响应容错解析（`GET /captures` → `R<PageResult<CaptureResponse>>`，见 02 §4.1.2）。
 *
 * <p>兼容 `{list}` / `{records}` / `{items}` + `total` 与裸数组四种形状，便于后端定稿后收紧；
 * 分页上限对齐 02 的 200（前端不主动传超过该值，但解析时不拦截）。
 *
 * @param {Object|Array|null} data 请求层兑现的 `data`
 * @returns {{list: Array<Object>, total: number, page: number, size: number}}
 */
export function parseCapturesPage(data) {
  if (Array.isArray(data)) return { list: data, total: data.length, page: 1, size: data.length }
  const src = data || {}
  let list = []
  if (Array.isArray(src.list)) list = src.list
  else if (Array.isArray(src.records)) list = src.records
  else if (Array.isArray(src.items)) list = src.items
  const total = typeof src.total === 'number' ? src.total : list.length
  const page = typeof src.page === 'number' ? src.page : 1
  const size = typeof src.size === 'number' ? src.size : list.length
  return { list, total, page, size }
}

/** `reason` 文案（02 §4.1.2：`NORMAL` 正常服药 / `MANUAL` 监护人主动留证）。 */
export const REASON_LABEL = {
  NORMAL: '正常服药',
  MANUAL: '主动留证',
}

/** `faceResult` 三态文案（TAKEN 已服 / ABSENT 未服 / UNCERTAIN 待确认）。 */
export const FACE_RESULT_LABEL = {
  TAKEN: '已服',
  ABSENT: '未服',
  UNCERTAIN: '待确认',
}

/**
 * 抓拍是否已过期（`expireAt` 早于当前时刻）。
 *
 * <p>02 §4.1：过期后 `/file` 返回 404/401，前端据此提示"原图已过期，无法访问"。
 * `expireAt` 缺失（后端未回）按未过期处理（交给后端最终判定）。
 *
 * @param {string} [expireAt] ISO-8601 UTC
 * @param {Date|number} [now=Date.now()]
 * @returns {boolean}
 */
export function isExpired(expireAt, now = new Date()) {
  const d = parseUtc(expireAt)
  if (!d) return false
  const base = now instanceof Date ? now : new Date(now)
  return d.getTime() < base.getTime()
}

/**
 * 抓拍视图归一化（列表项 / 查看器共用）：字段兜底 + 文案 + 时间本地化 + 待确认 / 过期标记。
 *
 * <p>只抽取页面需要字段，缺省兜底空串 / null / false，避免空值视图崩溃。后端未落地时
 * 各字段已做容错；后端定稿后仅改本文件即可收紧。
 *
 * @param {Object|null|undefined} data `CaptureResponse`（列表 item 或详情）
 * @returns {Object} 含 `captureId` / `fileToken` / `reason` / `reasonLabel` / `faceResultLabel` /
 *   `confidence` / `capturedAtText` / `expireAtText` / `uncertain` / `expired` / `thumbnailUrl`(需调用方补)
 */
export function toCaptureView(data) {
  const d = data || {}
  let confidence = null
  if (typeof d.confidence === 'number') confidence = d.confidence
  else if (typeof d.confidence === 'string' && d.confidence !== '') {
    const n = Number(d.confidence)
    if (!Number.isNaN(n)) confidence = n
  }
  const reason = d.reason || ''
  const faceResult = d.faceResult || ''
  const uncertain = faceResult === 'UNCERTAIN'
  return {
    captureId: d.captureId || '',
    deviceId: d.deviceId || '',
    elderId: d.elderId || '',
    medicineId: d.medicineId || '',
    planId: d.planId || '',
    reason,
    reasonLabel: REASON_LABEL[reason] || reason || '未知',
    faceResult,
    faceResultLabel: FACE_RESULT_LABEL[faceResult] || '',
    confidence,
    mime: d.mime || '',
    sizeBytes: typeof d.sizeBytes === 'number' ? d.sizeBytes : 0,
    capturedAt: d.capturedAt || '',
    capturedAtText: formatDateTime(d.capturedAt),
    viewedAt: d.viewedAt || '',
    expireAt: d.expireAt || '',
    expireAtText: formatDateTime(d.expireAt),
    fileToken: d.fileToken || '',
    uncertain,
    expired: isExpired(d.expireAt),
  }
}

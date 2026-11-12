/**
 * 抓拍 / 服药结论纯函数（固定 3 药格模型，对应 medbox-spec/02b §1.1、02e §1.1）。
 *
 * <p>设备上报以 `cellNo`（1/2/3）为唯一归属依据；取药结论二值化
 * `takenResult`（`TAKEN` 已取药 / `UNTAKEN` 未取药），不报置信度、无第三态、无「待确认」。
 * 仅 `UNTAKEN` 携带抓拍原图作留证。记录补录 / 纠错都不改库存。
 *
 * <p>本文件为纯函数，不引入 `@/config/index.js`（`uni.getStorageSync` 在 Vitest 下不可用）。
 */

import { formatDateTime, parseUtc } from '@/utils/datetime.js'

/** 固定格位中文标签（1 早间 / 2 午间 / 3 晚间）。 */
export function cellLabel(cellNo) {
  const no = Number(cellNo)
  if (no === 1) return '1 号格（早间）'
  if (no === 2) return '2 号格（午间）'
  if (no === 3) return '3 号格（晚间）'
  return '未识别格位'
}

/** 拼接抓拍原图访问 URL（fileToken 短时令牌，拼 query，不暴露长期 JWT）。 */
export function buildCaptureFileUrl(captureId, fileToken, baseUrl = '') {
  const id = encodeURIComponent(captureId || '')
  const tk = encodeURIComponent(fileToken || '')
  return `${baseUrl}/captures/${id}/file?token=${tk}`
}

/**
 * 抓拍元数据容错解析（`GET /captures/{captureId}` → `CaptureResponse`）。
 *
 * @param {Object|null|undefined} data 请求层兑现的 `data`
 * @returns {{captureId: string, fileToken: string, takenResult: string, cellNo: ?number, expireAt: string, mime: string}}
 */
export function parseCapture(data) {
  const d = data || {}
  const no = Number(d.cellNo)
  return {
    captureId: d.captureId || '',
    fileToken: d.fileToken || '',
    takenResult: d.takenResult || '',
    cellNo: Number.isInteger(no) && no >= 1 && no <= 3 ? no : null,
    expireAt: d.expireAt || '',
    mime: d.mime || '',
  }
}

/**
 * 组装补录 / 纠错请求体（`POST /records/{recordId}/confirm`）。
 *
 * <p>纠错：只传非空的 `actualTime` / `onTime` / `takenResult`；
 * 补录：额外必带 `elderId` / `planId` / `cellNo`（格号 1/2/3）。
 * 后端强制 `source=MANUAL`，前端不传；不传药品、剂量与 `note`。
 *
 * @param {Object} [opts] `{elderId?, planId?, cellNo?, actualTime?, onTime?, takenResult?}`
 * @returns {Object}
 */
export function buildConfirmPayload(opts) {
  const src = opts || {}
  const payload = {}
  const str = (value) => String(value == null ? '' : value).trim()
  const elderId = str(src.elderId)
  if (elderId) payload.elderId = elderId
  const planId = str(src.planId)
  if (planId) payload.planId = planId
  const no = Number(src.cellNo)
  if (Number.isInteger(no) && no >= 1 && no <= 3) payload.cellNo = no
  const actualTime = str(src.actualTime)
  if (actualTime) payload.actualTime = actualTime
  if (src.onTime === true || src.onTime === false) payload.onTime = src.onTime
  if (src.takenResult === 'TAKEN' || src.takenResult === 'UNTAKEN') {
    payload.takenResult = src.takenResult
  }
  return payload
}

/** 抓拍列表分页响应容错解析（兼容 `{list}` / `{records}` / `{items}` + `total` 与裸数组）。 */
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

/** `reason` 文案（NORMAL 正常服药 / MANUAL 监护人主动留证）。 */
export const REASON_LABEL = {
  NORMAL: '正常服药',
  MANUAL: '主动留证',
}

/** `takenResult` 二值文案。 */
export const TAKEN_RESULT_LABEL = {
  TAKEN: '已服',
  UNTAKEN: '未服',
}

/** 抓拍是否已过期（expireAt 缺失按未过期处理，交给后端最终判定）。 */
export function isExpired(expireAt, now = new Date()) {
  const d = parseUtc(expireAt)
  if (!d) return false
  const base = now instanceof Date ? now : new Date(now)
  return d.getTime() < base.getTime()
}

/**
 * 抓拍视图归一化（列表项 / 查看器共用）：字段兜底 + 文案 + 时间本地化 + 过期标记。
 * 只带格号，不带药品 ID。
 */
export function toCaptureView(data) {
  const d = data || {}
  const reason = d.reason || ''
  const takenResult = d.takenResult || ''
  const no = Number(d.cellNo)
  const cellNo = Number.isInteger(no) && no >= 1 && no <= 3 ? no : null
  return {
    captureId: d.captureId || '',
    deviceId: d.deviceId || '',
    elderId: d.elderId || '',
    planId: d.planId || '',
    cellNo,
    cellLabel: cellLabel(cellNo),
    reason,
    reasonLabel: REASON_LABEL[reason] || reason || '未知',
    takenResult,
    takenResultLabel: TAKEN_RESULT_LABEL[takenResult] || '',
    mime: d.mime || '',
    sizeBytes: typeof d.sizeBytes === 'number' ? d.sizeBytes : 0,
    capturedAt: d.capturedAt || '',
    capturedAtText: formatDateTime(d.capturedAt),
    viewedAt: d.viewedAt || '',
    expireAt: d.expireAt || '',
    expireAtText: formatDateTime(d.expireAt),
    fileToken: d.fileToken || '',
    expired: isExpired(d.expireAt),
  }
}

/**
 * 记录域接口（medbox-spec/02e_服药记录与依从性.md / openapi/02e-records.yaml）——全部需鉴权。
 *
 * <p>固定 3 药格模型：单条记录只带 `cellNo`（1/2/3），不含药品名称、剂量与「待确认」状态。
 * 所有接口错误码走 02 的 1.2 封闭集合：40101 / 40301 / 40302 / 40401 / 40001 / -1。
 */

import { get, post } from '@/api/request.js'

/**
 * 记录列表（分页 + 按时间 / 老人筛选）。
 *
 * @param {Object} params `{elderId, from?, to?, page?, size?}`（时间为 UTC ISO 8601）
 * @param {Object} [options] 透传给 `@/api/request` 的 `silent` 等
 * @returns {Promise<Object|Array>} 分页结构（容错见 `utils/record.parseRecordsPage`）；
 *   单条含 `recordId` / `elderId` / `planId` / `cellNo` / `planName` / `planTime` /
 *   `actualTime` / `onTime`(三态) / `takenResult` / `source`(CAMERA|MANUAL) /
 *   `captureId` / `hasCapture` / `deviceId` / `createdAt`
 * @throws {ApiError} 40101 / 40301 / 40302 / -1
 */
export function listRecords(params, options) {
  return get('/records', params, options)
}

/**
 * 记录详情（关联计划名与抓拍留证，不含药品信息）。
 *
 * @param {string} recordId 记录编号
 * @returns {Promise<Object>} 与列表单条同形状
 * @throws {ApiError} 40101 / 40301 / 40302 / 40401 / -1
 */
export function getRecord(recordId) {
  return get(`/records/${recordId}`)
}

/**
 * 补录 / 纠错（**单接口双语义**，仅 `WRITE_RECORD` = GUARDIAN）。
 *
 * <p>① 记录已存在 → 纠错：仅覆盖请求体里非空的 `actualTime` / `onTime` / `takenResult`，
 * `source` 恒置 `MANUAL`，不改 `planTime`、不改库存；
 * ② 记录不存在 → 补录：路径 `{recordId}` 被忽略，请求体必带
 * `elderId` / `planId` / `cellNo`（缺任一 40001，计划查不到 40401），
 * 由服务端生成新 `mr-` 号，`planTime` 按该格就近槽重算。
 *
 * @param {string} recordId 记录编号（新建分支被忽略）
 * @param {Object} [payload] `{elderId?, planId?, cellNo?, actualTime?, onTime?, takenResult?}`
 * @param {Object} [options] 透传请求选项
 * @returns {Promise<Object|null>} 更新 / 新建后的记录
 * @throws {ApiError} 40001 / 40101 / 40301 / 40302 / 40401 / -1
 */
export function confirmRecord(recordId, payload, options) {
  return post(`/records/${recordId}/confirm`, payload || {}, options)
}

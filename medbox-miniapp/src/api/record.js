/**
 * 记录域接口（`feat/mp-record`，对应 medbox-spec/02 第 7 章、06 的 2.7）。
 *
 * <p>**后端 `record-core` 未落地**：以下接口按 02 第 7 章先行实现，字段名（camelCase JSON）
 * 与现有 `plan` / `medicine` / `stock` 模块保持一致；真实后端定稿后若形状不同，
 * 仅在 `utils/record.js` 的 {@link parseRecordsPage} 一处做容错（本分支不在 02 第 7 章
 * 额外加"小程序侧落地口径"注释，待 record-core 定稿后由该文档统一收紧）。
 *
 * <p>**不在此导出** `GET /adherence` 与 `GET /records/export`：前者归 P2 `feat/mp-report`
 * （依从性统计），后者归同分支的导出能力，本期记录功能只做列表 / 详情 / 补录三件事。
 *
 * <p>所有接口需鉴权（默认 `auth: true`），错误码走 02 的 1.2 封闭集合：
 * 40101（登录失效，请求层自动续期）/ 40301（老人写 / 越权，FORBIDDEN）/ 40302
 * （监护关系 PENDING，GUARDIAN_PENDING）/ 40401（记录不存在，NOT_FOUND）/ -1（网络）。
 */

import { get, post } from '@/api/request.js'

/**
 * 记录列表（分页 + 按时间 / 老人筛选）。
 *
 * @param {Object} params
 * @param {string} [params.elderId] 老人业务 ID；监护人端传选中老人，老人端传自身（见 plan 模式）
 * @param {string} [params.from] 起始时间（UTC，ISO 8601，含；空则不限制）
 * @param {string} [params.to] 结束时间（UTC，ISO 8601，含；空则不限制）
 * @param {number} [params.page=1] 页码
 * @param {number} [params.size=20] 每页条数
 * @param {Object} [options] 透传给 `@/api/request` 的 `silent` 等
 * @returns {Promise<Object|Array>} 分页结构（容错见 `utils/record.parseRecordsPage`）；
 *   单条记录含 `recordId` / `elderId` / `planId` / `planItemId` / `medicineId` / `captureId` /
 *   `actualDose` / `planTime`(UTC) / `actualTime`(UTC) / `onTime`(bool) /
 *   `deviceId` / `planName` / `medicineName` / `medicineDose` / `medicineUnit` / `confirmedBy` /
 *   `confirmedAt`
 * @throws {ApiError} 40101 / 40301 / 40302 / -1
 */
export function listRecords(params, options) {
  return get('/records', params, options)
}

/**
 * 记录详情（关联计划与抓拍留证）。
 *
 * <p>抓拍留证（`capture-core`）属 P1，未落地时 `captureId` 为空，详情页缩略图区域留空占位
 * （`feat/mp-capture` 上线后由该分支补 `<image>` 与 fileToken 拼接，本期不调 `GET /captures`）。
 *
 * @param {string} recordId 记录编号（`record_id`）
 * @param {Object} [options]
 * @returns {Promise<Object>} 与列表单条同形状（额外含已 join 的计划 / 药品摘要）
 * @throws {ApiError} 40101 / 40301 / 40302 / 40401 / -1
 */
export function getRecord(recordId) {
  return get(`/records/${recordId}`)
}

/**
 * 监护人补录 / 确认（设备异常时手工校准）。
 *
 * <p>**仅 `WRITE_RECORD`（FAMILY）可见**：前端按角色隐藏入口（{@link canWriteRecord}），
 * 服务端仍强校验——老人写 40301、监护关系 PENDING 40302。本期补录内容以"确认/校准"为主，
 * `payload` 由页面组装（如 `actualDose` / `note`，后端未定稿前以最小契约传递）。
 *
 * @param {string} recordId 记录编号
 * @param {Object} [payload] 校准内容（后端 record-core 未定稿，尽量克制）
 * @param {Object} [options]
 * @returns {Promise<Object|null>} 更新后的记录（或 null）
 * @throws {ApiError} 40101 / 40301 / 40302 / 40401 / -1
 */
export function confirmRecord(recordId, payload, options) {
  return post(`/records/${recordId}/confirm`, payload || {}, options)
}

/**
 * 药品域接口（medbox-spec/02 第 5 章）—— 全部需鉴权（默认 `auth: true`，不带 Bearer 会 40101）。
 *
 * <p>`feat/mp-medicine` 只落地**药品档案 CRUD**（列表 / 详情 / 新建更新合一 / 删除）；
 * 库存相关接口（`/devices/{id}/stocks` 等）归 P0 第 5 步 `feat/mp-stock`，不在此导出。
 *
 * <p>⚠️ **后端 `medicine-core` 尚未落地**（07 清单未勾选）：本文件按 02 第 5 章 + 06 的 2.4
 * 先行实现，真机联调待后端合并；分页响应字段名未定处已做容错解析，见
 * `src/utils/medicine.js` 的 {@link parseMedicinePage} 注释。
 */

import { get, post, del } from '@/api/request.js'

/**
 * 药品列表：当前用户可见的药品字典（**分页**，02 的 1. 通用约定）。
 *
 * <p>可见范围 = 公共药品（`ownerElderId` 为空）+ 我监护老人的私有药品，由后端行级过滤
 * （02 的 1.3.1 `visibleElderIds()`），前端只传搜索词与分页参数，**不做本地过滤**。
 *
 * @param {Object} [params]
 * @param {string} [params.keyword] 搜索关键词（匹配药品名；空串由请求层自动丢弃）
 * @param {number} [params.page=1] 页码（从 1 起）
 * @param {number} [params.size=20] 每页条数（上限 100）
 * @param {Object} [options] 透传请求选项（页面自绘错误时传 `silent: true`）
 * @returns {Promise<{list: Array<Object>, total: number}>} 分页结果（经 {@link parseMedicinePage} 容错）
 * @throws {ApiError} 40001 / 40101 / 40301 / 40302 / -1（网络）
 */
export function listMedicines(params, options) {
  return get('/medicines', params, options)
}

/**
 * 药品详情。
 *
 * @param {string} medicineId 药品编号（如 `m-205`）
 * @param {Object} [options] 透传请求选项
 * @returns {Promise<Object>} `medicineId` / `ownerElderId`(空=公共字典) / `name` / `spec` /
 *   `dosePerTime`(默认单次剂量) / `unit` / `contraindication`(禁忌) /
 *   `storageCond`(储存条件) / `createdAt`(UTC)
 * @throws {ApiError} 40101 / 40301（他人私有药品）/ 40401 / -1
 */
export function getMedicine(medicineId, options) {
  return get(`/medicines/${medicineId}`, undefined, options)
}

/**
 * 新建 / 更新药品档案（**02 第 5 章为同一入口**：更新时带上 `medicineId`）。
 *
 * <p>**不传 `ownerElderId` 即公共字典**（全体可见只读）；归属某老人则传其 `userId`。
 *
 * @param {Object} payload 请求体（由 `src/utils/medicine.js` 的 {@link buildMedicinePayload} 组装）：
 *   `medicineId`(仅编辑) / `name`(必填) / `spec` / `dosePerTime` / `unit` /
 *   `contraindication` / `storageCond` / `ownerElderId`(不传 = 公共)
 * @param {Object} [options] 透传请求选项（如 `loading: '保存中'`）
 * @returns {Promise<Object>} 保存后的药品档案
 * @throws {ApiError} 40001（`name` 为空等）/ 40101 / 40301（老人只读、越权）/ 40302 / -1
 */
export function saveMedicine(payload, options) {
  return post('/medicines', payload, options)
}

/**
 * 删除药品档案：**无任何药箱库存引用时才允许**（02 第 5 章）。
 *
 * @param {string} medicineId 药品编号
 * @param {Object} [options] 透传请求选项（弹窗提交场景传 `silent: true` 由页面兜底）
 * @returns {Promise<null>} 成功时 `data` 通常为 null（后端 `non_null` 下字段直接缺失）
 * @throws {ApiError} **40901（仍被库存引用）** / 40101 / 40301 / 40401 / -1
 */
export function deleteMedicine(medicineId, options) {
  return del(`/medicines/${medicineId}`, undefined, options)
}

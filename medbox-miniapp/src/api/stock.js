/**
 * 药箱库存接口（medbox-spec/02 第 5 章）—— 全部需鉴权（默认 `auth: true`，不带 Bearer 会 40101）。
 *
 * <p>`feat/mp-stock` 落地"库存四条"：清单 GET / 录入更新 PUT / 补药入库 POST .../in /
 * 移除 DELETE。
 *
 * <p>⚠️ **后端 `medicine-core` 尚未落地**（07 清单未勾选）：本文件按 02 第 5 章 + 06 的 2.5
 * 先行实现，真机联调待后端合并；响应形状与请求体字段名假设见 `src/utils/stock.js` 的
 * {@link parseStocks} 注释与 02 第 5 章"库存四条 · 小程序侧口径"。
 */

import { get, put, post, del } from '@/api/request.js'

/**
 * 药箱库存清单：按药品展示数量、生产日期、有效期（06 的 2.5）。
 *
 * <p>**行级字段不含药品名**（`medicine_stock` 没有 name 列），名称 / 规格 / 单位由前端
 * join 药品字典（`listMedicines`）得到，见 `src/utils/stock.js` 的 {@link joinStockMedicine}。
 *
 * @param {string} deviceId 药箱**主控**编号（传子设备编号会 40301）
 * @param {Object} [options] 透传请求选项（页面自绘错误时传 `silent: true`）
 * @returns {Promise<Array<Object>>} 库存行数组（经 {@link parseStocks} 容错）：每项
 *   `stockId` / `deviceId` / `elderId` / `medicineId` / `quantity`(INT) /
 *   `productionDate`(DATE) / `expiryDate`(DATE) / `updatedAt`(UTC)
 * @throws {ApiError} 40101 / 40301（他人设备 / 设备不存在）/ 40302 / -1（网络）
 */
export function listStocks(deviceId, options) {
  return get(`/devices/${deviceId}/stocks`, undefined, options)
}

/**
 * 录入 / 更新某药品库存（**`quantity` 为绝对值**，覆盖式写入）。
 *
 * @param {string} deviceId 药箱主控编号
 * @param {string} medicineId 药品编号
 * @param {Object} payload `{ quantity, productionDate?, expiryDate? }`
 *   （由 `src/utils/stock.js` 的 {@link buildStockPayload} 组装；日期为 `YYYY-MM-DD`，可省）
 * @param {Object} [options] 透传请求选项（如 `loading: '保存中'`）
 * @returns {Promise<Object>} 保存后的库存行
 * @throws {ApiError} 40001（数量非法等）/ 40101 / 40301 / 40302 / -1
 */
export function saveStock(deviceId, medicineId, payload, options) {
  return put(`/devices/${deviceId}/stocks/${medicineId}`, payload, options)
}

/**
 * 补药入库（**`quantity` 为增量**，在现有库存基础上增加；可同时更新生产日期 / 有效期）。
 *
 * @param {string} deviceId 药箱主控编号
 * @param {string} medicineId 药品编号
 * @param {Object} payload `{ quantity, productionDate?, expiryDate? }`
 *   （{@link buildRestockPayload} 组装；`quantity` 为入库增量，日期选填）
 * @param {Object} [options] 透传请求选项
 * @returns {Promise<Object>} 更新后的库存行
 * @throws {ApiError} 40001 / 40101 / 40301 / 40302 / -1
 */
export function restockStock(deviceId, medicineId, payload, options) {
  return post(`/devices/${deviceId}/stocks/${medicineId}/in`, payload, options)
}

/**
 * 移除该药品库存（02 第 5 章库存四条之一）。
 *
 * @param {string} deviceId 药箱主控编号
 * @param {string} medicineId 药品编号
 * @param {Object} [options] 透传请求选项（弹窗提交场景传 `silent: true` 由页面兜底）
 * @returns {Promise<null>} 成功时 `data` 通常为 null（后端 `non_null` 下字段直接缺失）
 * @throws {ApiError} 40101 / 40301 / 40401 / -1
 */
export function deleteStock(deviceId, medicineId, options) {
  return del(`/devices/${deviceId}/stocks/${medicineId}`, undefined, options)
}

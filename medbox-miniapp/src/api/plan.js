/**
 * 服药计划接口（medbox-spec/02d_服药计划与提醒.md / openapi/02d-plans.yaml）——全部需鉴权。
 *
 * <p>固定 3 药格模型：计划只含 `slots`（`[{cellNo: 1|2|3, time: "HH:mm"}]`），
 * 不关联药品、剂量与库存。`slots[].time` 为**设备本地墙钟**（如 `"08:00"`），不是 UTC；
 * 前端只做字符串校验与按格号排序，**不做任何时区换算**。
 *
 * <p>一老人一计划（`med_plan.elder_id` 唯一）：`GET /plans?elderId=` 返回 0 或 1 条；
 * 重复 `POST /plans` 返回 40901「该老人已有计划」。PUT 为整体覆盖，未提交的格号即停用。
 */

import { get, post, put, del, request } from '@/api/request.js'

/**
 * 计划列表（一老人一计划，返回 0 或 1 条）。
 *
 * @param {Object} [params] `{elderId, page, size}`
 * @param {Object} [options] 透传请求选项（页面自绘错误时传 `silent: true`）
 * @returns {Promise<{list: Array<Object>, total: number}>} 分页结果（经 `parsePlansPage` 容错）；
 *   每项 `planId` / `elderId` / `name` / `slots` / `repeatRule` / `status` / `syncState` / `createdAt`
 * @throws {ApiError} 40001 / 40101 / 40301 / 40302 / -1
 */
export function listPlans(params, options) {
  return get('/plans', params, options)
}

/**
 * 计划详情（含 `slots`，不含药品明细）。
 *
 * @param {string} planId 计划编号
 * @param {Object} [options] 透传请求选项
 * @returns {Promise<Object>} `planId` / `elderId` / `name` / `slots` / `repeatRule` /
 *   `missAlarmAfterMin` / `alarmRule` / `status` / `syncState` / `createdAt`
 * @throws {ApiError} 40101 / 40301 / 40302 / 40401 / -1
 */
export function getPlan(planId, options) {
  return get(`/plans/${planId}`, undefined, options)
}

/**
 * 创建服药计划（只提交格位排程）。
 *
 * @param {Object} payload `{elderId, name?, slots:[{cellNo, time}], repeatRule?}`
 *   （由 `src/utils/plan.js` 的 `buildPlanPayload` 组装）
 * @param {Object} [options] 透传请求选项（如 `loading: '保存中'`）
 * @returns {Promise<Object>} 保存后的计划（含 `planId` / `slots` / `syncState`）
 * @throws {ApiError} 40001（slots 为空 / 格号越界或重复 / time 格式非法）/ 40101 / 40301 /
 *   40302 / **40901（该老人已有计划）** / -1
 */
export function createPlan(payload, options) {
  return post('/plans', payload, options)
}

/**
 * 修改计划（**整体覆盖**：`slots` / 周期 / 报警规则全量提交，未提交的格号即停用）。
 *
 * @param {string} planId 计划编号
 * @param {Object} payload 同 {@link createPlan}（不含 `planId`）
 * @param {Object} [options] 透传请求选项
 * @returns {Promise<Object>} 保存后的计划
 * @throws {ApiError} 同 {@link createPlan}
 */
export function updatePlan(planId, payload, options) {
  return put(`/plans/${planId}`, payload, options)
}

/**
 * 启用 / 停用计划（请求体 `{status: 'ENABLED'|'DISABLED'}`）。
 *
 * @param {string} planId 计划编号
 * @param {'ENABLED'|'DISABLED'} status 目标状态
 * @param {Object} [options] 透传请求选项（开关场景传 `silent: true`）
 * @returns {Promise<Object>} 更新后的计划（含 `status` / `syncState`）
 * @throws {ApiError} 40001（status 非法）/ 40101 / 40301 / 40302 / 40401 / -1
 */
export function updatePlanStatus(planId, status, options) {
  return request({
    url: `/plans/${planId}/status`,
    method: 'PATCH',
    data: { status },
    ...(options || {}),
  })
}

/**
 * 删除计划（一老人一计划，无明细级联）。
 *
 * @param {string} planId 计划编号
 * @param {Object} [options] 透传请求选项（弹窗提交场景传 `silent: true`）
 * @returns {Promise<null>} 成功时 `data` 通常为 null
 * @throws {ApiError} 40101 / 40301 / 40302 / 40401 / -1
 */
export function deletePlan(planId, options) {
  return del(`/plans/${planId}`, undefined, options)
}

/**
 * 未来待服时间线（按格展开，每条含 `cellNo`，不含药品信息）。
 *
 * <p>`days` 默认 7、上限 60，本期仅 `DAILY`，停用计划返回空；`scheduledTimeLocal`
 * 由后端按老人 MAIN 设备 `device.timezone` 换算好，**前端只展示、不换算**。
 *
 * @param {string} planId 计划编号
 * @param {Object} [query] `{days}`
 * @param {Object} [options] 透传请求选项
 * @returns {Promise<Object|Array>} 请求层兑现的 `data`（调用方用 `parseNextDoses` 归一化）
 * @throws {ApiError} 40101 / 40301 / 40401 / -1
 */
export function getNextDoses(planId, query, options) {
  const days = Number((query && query.days) || 7)
  return get(`/plans/${planId}/next-doses`, { days }, options)
}

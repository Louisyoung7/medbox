/**
 * 服药计划接口（medbox-spec/02 第 6 章）—— 全部需鉴权（默认 `auth: true`）。
 *
 * <p>`feat/mp-plan` 落地计划 CRUD 与启停；`GET /plans/{planId}/next-doses`（未来待服时间线）
 * 由 P1 `feat/mp-plan-timeline` 在此导出（见 {@link getNextDoses}）。
 *
 * <p>后端 `plan-core`（含 `next-doses`）已随 07 清单落地（`plan-sync` 亦已落地）：分页响应字段名与
 * PATCH 请求体已定稿，前端保留 {@link parsePlansPage} 容错与 02 第 6 章"小程序侧口径"；`next-doses`
 * 响应形状以后端为准，前端解析器（{@link parseNextDoses}）做容错。
 *
 * <p>**时区口径（02/06/01 三处一致）**：`times` 为**设备本地墙钟时间**（如 `"08:00"`），
 * 不是 UTC；前端只做字符串校验与排序，**不做任何时区换算**。
 */

import { get, post, put, del, request } from '@/api/request.js'

/**
 * 计划列表：按老人查询（**列表项含药品概览**，02 第 6 章）。
 *
 * @param {Object} [params]
 * @param {string} params.elderId 老人 `userId`（老人端传自身；监护人端传所选老人）
 * @param {number} [params.page=1] 页码（从 1 起）
 * @param {number} [params.size=20] 每页条数
 * @param {Object} [options] 透传请求选项（页面自绘错误时传 `silent: true`）
 * @returns {Promise<{list: Array<Object>, total: number}>} 分页结果（经 {@link parsePlansPage} 容错）；
 *   每项 `planId` / `elderId` / `name` / `times` / `repeatRule` / `status` / `syncState` /
 *   `items`（药品概览）/ `createdAt`
 * @throws {ApiError} 40001 / 40101 / 40301 / 40302 / -1（网络）
 */
export function listPlans(params, options) {
  return get('/plans', params, options)
}

/**
 * 计划详情（含 `items` 明细）。
 *
 * @param {string} planId 计划编号
 * @param {Object} [options] 透传请求选项
 * @returns {Promise<Object>} `planId` / `elderId` / `name` / `times` / `repeatRule` /
 *   `status` / `syncState` / `items`（每项 `itemId` / `medicineId` / `dose` / `unit` / `note` / `sortOrder`）
 * @throws {ApiError} 40101 / 40301 / 40302 / 40401 / -1
 */
export function getPlan(planId, options) {
  return get(`/plans/${planId}`, undefined, options)
}

/**
 * 创建服药计划（**一次提醒可含多种药**，请求体带 `items` 数组）。
 *
 * @param {Object} payload `{elderId, name, times, repeatRule?, items:[{medicineId, dose?, unit?, note?}]}`
 *   （由 `src/utils/plan.js` 的 {@link buildPlanPayload} 组装；**不传** `missAlarmAfterMin` /
 *   `alarmRule`，走后端默认与老人级配置，见 02 第 6 章"小程序侧口径"）
 * @param {Object} [options] 透传请求选项（如 `loading: '保存中'`）
 * @returns {Promise<Object>} `{planId, syncState, items:[{itemId, medicineId}]}`
 * @throws {ApiError} 40001（times 格式 / items 为空 / 同计划药品重复）/ 40101 / 40301 / 40302 /
 *   **40901（药品无库存或已过期，06 的 2.6.1 约束②）** / -1
 */
export function createPlan(payload, options) {
  return post('/plans', payload, options)
}

/**
 * 修改计划（**整体覆盖**：时间 / 周期与 `items` 明细一并提交，02 第 6 章）。
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
 * 启用 / 停用计划。
 *
 * <p>⚠️ 02 第 6 章未定义请求体：**前端先行契约为 `{status: 'ENABLED'|'DISABLED'}`**（取值对照
 * 06 的 2.6 `status` 默认 `ENABLED`），后端 `plan-core` 定稿后对齐并同步 02。
 *
 * @param {string} planId 计划编号
 * @param {'ENABLED'|'DISABLED'} status 目标状态
 * @param {Object} [options] 透传请求选项（开关场景传 `silent: true` 页面自绘）
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
 * 删除计划（**级联删除明细**，06 的 2.6.1 约束③）。
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
 * 未来待服药时间线（按次展开，每次列出所需药品；02 第 6 章、feat/mp-plan-timeline）。
 *
 * <p>后端已落地（`plan-core`）：`days` 默认 7、上限 60，本期仅 `DAILY`，停用计划返回空；
 * `scheduledTimeLocal` 由后端按老人 MAIN 设备 `device.timezone` 换算好，**前端只展示、不换算**。
 *
 * @param {string} planId 计划编号
 * @param {Object} [query]
 * @param {number} [query.days=7] 展望天数（1~60，越界由后端 40001 拦截；缺省 7）
 * @param {Object} [options] 透传请求选项（页面自绘错误传 `silent: true`）
 * @returns {Promise<Object|Array>} 请求层兑现的 `data`（外壳见后端，调用方用 {@link parseNextDoses} 归一化）
 * @throws {ApiError} 40101 / 40301（非本人/非监护人）/ 40401（计划不存在）/ -1
 */
export function getNextDoses(planId, query, options) {
  const days = Number((query && query.days) || 7)
  return get(`/plans/${planId}/next-doses`, { days }, options)
}

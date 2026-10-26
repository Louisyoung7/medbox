/**
 * 告警域接口（medbox-spec/02 第 8 章）—— 全部需鉴权（默认 `auth: true`，不带 Bearer 会 40101）。
 *
 * <p>`feat/mp-alarm` 落地告警列表/未读/已读/处理 + 阈值设置；实时告警经 WebSocket（见
 * `src/store/ws.js` + `src/components/AlarmRealtime.vue`），不在此导出。后端 `alarm-core`
 * 已落地（07 P1，2026-10-26）。
 *
 * <p>权限矩阵（02 第 8 章，feat/alarm-core 落地口径）：
 * - `GET /alarms`、`GET /alarms/unread-count`、`PATCH /alarms/{id}/read`、`GET /alarm-settings`
 *   → `READ_ALARM`（四角色可读，前端不拦）；
 * - `PATCH /alarms/{id}/handle` → `HANDLE_ALARM`（FAMILY + NURSE，前端 `RoleGate entry="alarmHandle"`）；
 * - `PUT /alarm-settings` → `WRITE_ALARM_SETTINGS`（仅 FAMILY，DOCTOR/NURSE/ELDER 写返回 40301，
 *   前端 `RoleGate entry="alarmSettings"` 包裹设置页）。
 */

import { get, patch, put } from '@/api/request.js'

/**
 * 告警分页（按 `created_at` 倒序：最新在前；`page` 从 1 起、默认 size 20）。
 *
 * @param {{elderId:string, type?:string, readState?:string, page?:number, size?:number}} query
 *   `type` / `readState` 传 `'ALL'` 视为不过滤（请求层会丢弃空串，但显式 `'ALL'` 也会丢弃，见下）。
 * @returns {Promise<{list:Array, total:number}>}
 * @throws {ApiError} 40001（分页参数非法）/ 40101 / 40301（越权/不存在）/ -1
 */
export function getAlarms(query) {
  const q = { ...query }
  // 'ALL' 是前端筛选占位，不该传给后端；与 device 事件同口径，交给请求层丢弃空串
  if (q.type === 'ALL') delete q.type
  if (q.readState === 'ALL') delete q.readState
  return get('/alarms', q)
}

/**
 * 未读告警数（`GET /alarms/unread-count`，驱动角标）。
 *
 * @param {{elderId:string}} query
 * @returns {Promise<{count:number}>}
 * @throws {ApiError} 40101 / 40301 / -1
 */
export function getUnreadCount(query) {
  return get('/alarms/unread-count', query || {})
}

/**
 * 标记已读（`PATCH /alarms/{alarmId}/read`，`READ_ALARM` 四角色）。
 *
 * @param {string} alarmId
 * @returns {Promise<null>}
 * @throws {ApiError} 40101 / 40301 / 40401（告警不存在）/ -1
 */
export function markAlarmRead(alarmId) {
  return patch(`/alarms/${alarmId}/read`)
}

/**
 * 处理/确认告警（`PATCH /alarms/{alarmId}/handle`，`HANDLE_ALARM` = FAMILY + NURSE）。
 *
 * @param {string} alarmId
 * @returns {Promise<null>}
 * @throws {ApiError} 40101 / 40301（非 FAMILY+NURSE）/ 40401 / -1
 */
export function handleAlarm(alarmId) {
  return patch(`/alarms/${alarmId}/handle`)
}

/**
 * 查询当前告警配置（`GET /alarm-settings`，`READ_ALARM`）。
 *
 * @param {{elderId:string}} query
 * @returns {Promise<Object>} 设置对象（缺字段用 {@link toAlarmSettingsView} 兜底）
 * @throws {ApiError} 40101 / 40301 / -1
 */
export function getAlarmSettings(query) {
  return get('/alarm-settings', query || {})
}

/**
 * 配置告警规则与阈值（`PUT /alarm-settings`，`WRITE_ALARM_SETTINGS` 仅 FAMILY）。
 *
 * <p>后端字段可选、仅提供项覆盖原值；`elderId` 走 query，`body` 为阈值（提前期 / 环境上下限 /
 * 漏服容忍）。写操作，自动注入 `X-Request-Id`。
 *
 * @param {{elderId:string}} query
 * @param {Object} settings 阈值对象（可只传要改的字段）
 * @returns {Promise<null>}
 * @throws {ApiError} 40101 / 40301（非 FAMILY）/ -1
 */
export function saveAlarmSettings(query, settings) {
  return put('/alarm-settings', settings || {}, { params: query || {} })
}

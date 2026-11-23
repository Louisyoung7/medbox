/**
 * 告警实时状态（feat/mp-alarm）：未读角标计数 + 可见老人推导 + WS 入口封装。
 *
 * <p>依赖 `src/store/ws.js`（唯一长连接）与 `src/api/alarm.js`（REST 未读计数）。
 *
 * <p>设计要点：
 * - `unreadCount` 为响应式（供 WsStatusBar 角标 / 首页告警卡实时刷新）；
 * - 监护人"可见老人"由 `GET /devices` 推导（每台主控的 `elderId` 即一位；`feat/guardian-relation`
 *   未落地前不猜订阅范围，与 mp-ws 约定一致）；老人端用自身 `userId`；
 * - `currentElderId` 用于角标/列表默认查询（监护人取推导出的第一个；列表页切换老人时回写）。
 */

import { reactive } from 'vue'
import { getAlarms, getUnreadCount } from '@/api/alarm.js'
import { getUserInfo } from '@/store/user.js'
import { listDevices } from '@/api/device.js'

/** 响应式状态：未读数 / 当前老人 / 可见老人列表 / 回前台补拉的最新记录。 */
const state = reactive({
  unreadCount: 0,
  currentElderId: '',
  elderIds: [],
  latest: [],
  refreshTick: 0,
})

/** 取响应式状态（组件 `computed` 里引用以跟踪变化）。 */
export function getAlarmState() {
  return state
}

/** 设置可见老人列表（由 `loadVisibleElderIds` 写入；列表页切换老人不在此改）。 */
export function setVisibleElderIds(ids) {
  state.elderIds = Array.isArray(ids) ? ids : []
}

/** 设置"当前老人"（角标 / 列表默认查询用；监护人列表页切换老人时回写）。 */
export function setCurrentElderId(id) {
  state.currentElderId = id || ''
}

/** 未读数 +1（收到 `ALARM` 帧时调用）。 */
export function bumpUnreadCount(by) {
  state.unreadCount += typeof by === 'number' ? by : 1
}

/** 未读数清零（退出登录 / 切换账号时）。 */
export function resetAlarmUnread() {
  state.unreadCount = 0
  state.currentElderId = ''
  state.elderIds = []
  state.latest = []
}

/**
 * 拉取未读告警数（进入前台 / 列表刷新 / WS 收到告警后调用）。
 *
 * @param {string} [elderId] 不传用 `currentElderId`
 */
export async function refreshUnreadCount(elderId) {
  const eid = elderId || state.currentElderId
  if (!eid) return
  try {
    const data = await getUnreadCount({ elderId: eid })
    state.unreadCount = data && typeof data.count === 'number' ? data.count : 0
  } catch (err) {
    console.warn('[alarm] 未读计数刷新失败', err && err.code ? err.code : err)
  }
}

/**
 * 回前台补拉（`feat/app-runtime`）：未读角标 + 最新 5 条记录。
 *
 * <p>**去重口径**：实时弹窗只由 WS 的 `ALARM` 帧驱动；本函数只刷新角标与 `latest` 列表
 * （列表页靠 `refreshTick` 或重新进入刷新），不重复弹窗。App 切后台后进程可能被挂起、
 * WS 断连期间漏掉的告警靠这次补拉兜底。
 *
 * @returns {Promise<Array>} 最新记录（失败时保留上一次的 `latest`）
 */
export async function refreshAfterForeground() {
  const eid = state.currentElderId
  if (!eid) return state.latest
  await refreshUnreadCount(eid)
  try {
    const data = await getAlarms({ elderId: eid, page: 1, size: 5 })
    state.latest = data && Array.isArray(data.list) ? data.list : []
    state.refreshTick += 1
  } catch (err) {
    console.warn('[alarm] 回前台补拉最新记录失败', err && err.code ? err.code : err)
  }
  return state.latest
}

/**
 * 推导当前用户的可见老人（缓存到 `state`）：老人用自身 `userId`；监护人由 `GET /devices`
 * 取所有 MAIN 主控的 `elderId`（去重）。
 *
 * @returns {Promise<string[]>}
 */
export async function loadVisibleElderIds() {
  const info = getUserInfo()
  if (!info || !info.userId) {
    state.elderIds = []
    state.currentElderId = ''
    return []
  }
  if (info.role === 'ELDER') {
    state.elderIds = [info.userId]
    state.currentElderId = info.userId
    return state.elderIds
  }
  try {
    const devices = await listDevices()
    const ids = Array.from(
      new Set(
        (devices || [])
          .filter((d) => d.deviceType === 'MAIN')
          .map((d) => d.elderId)
          .filter(Boolean),
      ),
    )
    state.elderIds = ids
    if (!state.currentElderId || ids.indexOf(state.currentElderId) === -1) {
      state.currentElderId = ids[0] || ''
    }
    return ids
  } catch (err) {
    console.warn('[alarm] 推导可见老人失败', err && err.code ? err.code : err)
    return []
  }
}

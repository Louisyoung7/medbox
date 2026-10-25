/**
 * 个人资料域纯函数（`feat/mp-profile`，对应 medbox-spec/02 第 3 章、01 第 3 章）。
 *
 * <p>只放与页面解耦的可单测逻辑：资料字段兜底文案、角色权限一句说明、老人姓名 join、
 * "要不要静默补齐 /users/me"的判定。**不发起请求、不读写存储** —— 那些在页面与
 * `store/user.js`。
 *
 * <p>**非判定型**：监护关系的权限判定（40301 / 40302）全在服务端（02 的 3.2 / 1.3），
 * 本模块只把服务端返回渲染成文案。
 */

import { ROLE } from '@/store/user.js'

/**
 * 资料字段兜底：null / undefined / 空串 / 纯空白 → "未设置"。
 *
 * <p>后端全局 non_null 会**省略** null 字段（如老人无 `username` 则不返回该键），
 * 页面不能把"字段缺失"显示成 "null" 或空行。
 *
 * @param {*} value `/users/me` 或 `/users/{elderId}/profile` 的字段值
 * @returns {string}
 */
export function profileFieldText(value) {
  if (value === null || value === undefined) return '未设置'
  const text = String(value).trim()
  return text || '未设置'
}

/**
 * 角色权限一句说明（文案口径取 01 第 3 章与 02 的 1.3 权限矩阵）。
 *
 * <p>**只覆盖注册角色** `ELDER` / `GUARDIAN`：护理 / 医生是 `guardian_relation.role`
 * （FAMILY / DOCTOR / NURSE），不在 `user.role` 里，本端也拿不到，故不展示。
 *
 * @param {string} [role] `store/user.js` 的角色
 * @returns {string} 未知角色返回空串（页面不渲染该行说明）
 */
export function rolePermissionText(role) {
  switch (role) {
    case ROLE.ELDER:
      return '只读本人数据（计划 / 记录 / 告警 / 设备状态），可用 AI 问答'
    case ROLE.GUARDIAN:
      return '对已确认绑定的老人可读可写（计划、药品、库存、告警处理）'
    default:
      return ''
  }
}

/**
 * 老人姓名 join（监护页「我的申请」卡用）：有姓名 → "张桂英（u-1001）"，否则 elderId 原文。
 *
 * <p>**姓名是增强信息**：`GET /users/{elderId}/profile` 失败（40302 申请中未生效 /
 * 40301 被拒或无关系 / 网络错）时调用方传 `null`，本函数回退 elderId —— 布局不跳变，
 * 权限错误不打扰用户。
 *
 * @param {{name?: string}|null} profile `/users/{elderId}/profile` 的响应（失败传 null）
 * @param {string} elderId 老人用户ID（回退展示用）
 * @returns {string}
 */
export function elderNameText(profile, elderId) {
  const name = profile && typeof profile.name === 'string' ? profile.name.trim() : ''
  if (!name) return elderId || ''
  return elderId ? `${name}（${elderId}）` : name
}

/**
 * 本地缓存是否需要静默补齐（决定 profile 页 `onShow` 要不要拉一次 `/users/me`）。
 *
 * <p>登录时 `getMe` 失败（网络抖动）会留下缺 `name` / `phone` 的半份缓存；**缺关键字段才拉**，
 * 不缺不拉 —— TabBar 页 `onShow` 每次切 tab 都触发，无条件拉会把"看资料"变成
 * "每次进页一个请求"（`restoreSession` 正常时缓存是满的，不会触发）。
 *
 * @param {Object} [info] `store/user.js` 的 `getUserInfo()` 结果
 * @returns {boolean}
 */
export function needsProfileRefresh(info) {
  if (!info) return true
  return !info.name || !info.phone
}

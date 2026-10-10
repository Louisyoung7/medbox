/**
 * 当前登录用户的资料缓存（`feat/mp-auth`，对应 medbox-spec/02 第 3 章 `GET /users/me`）。
 *
 * <p>**与 `src/store/token.js` 的分工**：`token.js` 只管凭据（access / refresh token），
 * 本文件只管用户资料（`userId` / `role` / `name` / `phone`），二者都由
 * `src/store/auth.js` 统一编排读写 —— 业务代码不要绕过它直接改这里，避免"token 没了但
 * 用户资料还在"的半登录态。
 *
 * <p>同样用 `uni.getStorageSync` 同步读写（与 token / 配置一致）：页面 `onShow` 里要同步
 * 拿到角色来决定显示哪些入口。
 */

/** 用户资料的本地缓存 key。 */
export const USER_INFO_KEY = 'userInfo'

/**
 * 注册与角色判断用的角色枚举（见 06 的 2.1 `user.role`）。
 *
 * <p>**只有这两个值**：社区护理 / 家庭医生是 `guardian_relation.role`
 * （`FAMILY` / `DOCTOR` / `NURSE`），不是用户的注册角色。
 */
export const ROLE = {
  ELDER: 'ELDER',
  GUARDIAN: 'GUARDIAN',
}

/** 角色中文名（页面展示用；未知角色原样返回，便于将来扩展）。 */
const ROLE_LABEL = {
  [ROLE.ELDER]: '老人',
  [ROLE.GUARDIAN]: '监护人',
}

/**
 * 取缓存的用户资料。
 * @returns {Object} 未登录 / 未拉取时为空对象
 */
export function getUserInfo() {
  return uni.getStorageSync(USER_INFO_KEY) || {}
}

/**
 * 写入用户资料（登录成功与 `restoreSession` 校验通过时调用）。
 *
 * @param {Object} info `GET /users/me` 或登录响应的用户字段（未知字段也一并缓存，
 *   后续分支如 mp-profile 可直接取用）
 */
export function setUserInfo(info) {
  if (!info) {
    return
  }
  uni.setStorageSync(USER_INFO_KEY, info)
}

/** 退出登录 / 40101 续期失败时清除用户资料（与 `clearTokens()` 成对调用）。 */
export function clearUserInfo() {
  uni.removeStorageSync(USER_INFO_KEY)
}

/**
 * 当前用户角色。
 * @returns {string} 未登录为空串
 */
export function getRole() {
  return getUserInfo().role || ''
}

/**
 * 当前用户业务 ID（`user_id`，如 `u-1001`）。
 * @returns {string} 未登录为空串
 */
export function getUserId() {
  return getUserInfo().userId || ''
}

/**
 * 角色中文名。
 * @param {string} [role] 不传取当前缓存的角色
 * @returns {string}
 */
export function roleLabel(role) {
  const value = role || getRole()
  return ROLE_LABEL[value] || value || '未登录'
}

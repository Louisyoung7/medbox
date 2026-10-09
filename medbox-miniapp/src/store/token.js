/**
 * Token 本地存储（最小可用版本，供 `feat/mp-request` 注入鉴权头）。
 *
 * <p>**归属约定**：`src/store/` 归 `feat/mp-auth`（登录注册与 token 管理）所有，本文件是它的
 * 起点 —— mp-auth 在此扩展「刷新、过期判断、用户信息」，**不要另建平行模块**，否则请求层
 * 与登录页会读到两份 token。
 *
 * <p>用 `uni.getStorageSync` 同步读写：与 `src/config/index.js` 读 `serverHost` 的写法一致，
 * 且请求发起前必须同步拿到 token（异步读会让鉴权头丢空）。
 */

/** access token 的本地缓存 key（2 小时有效，见 02 第 3 章）。 */
export const ACCESS_TOKEN_KEY = 'accessToken'

/** refresh token 的本地缓存 key（7 天有效、换新即作废旧的）。 */
export const REFRESH_TOKEN_KEY = 'refreshToken'

/**
 * 取当前 access token。
 * @returns {string} 未登录为空串
 */
export function getAccessToken() {
  return uni.getStorageSync(ACCESS_TOKEN_KEY) || ''
}

/**
 * 取当前 refresh token。
 * @returns {string} 未登录为空串
 */
export function getRefreshToken() {
  return uni.getStorageSync(REFRESH_TOKEN_KEY) || ''
}

/**
 * 登录后写入 token 对。
 *
 * @param {Object} tokens
 * @param {string} [tokens.token] access token（02 第 3 章登录响应里的 `data.token`）
 * @param {string} [tokens.refreshToken] refresh token
 */
export function setTokens({ token, refreshToken } = {}) {
  if (token) {
    uni.setStorageSync(ACCESS_TOKEN_KEY, token)
  }
  if (refreshToken) {
    uni.setStorageSync(REFRESH_TOKEN_KEY, refreshToken)
  }
}

/** 退出登录 / 40101 时清除本地凭据。 */
export function clearTokens() {
  uni.removeStorageSync(ACCESS_TOKEN_KEY)
  uni.removeStorageSync(REFRESH_TOKEN_KEY)
}

/** 是否已有 access token（路由守卫与"是否已登录"判断用）。 */
export function hasToken() {
  return getAccessToken() !== ''
}

/**
 * 认证与用户接口（medbox-spec/02 第 3 章）—— 请求封装的首个业务消费者。
 *
 * <p>`feat/mp-auth`（登录注册与 token 管理）**直接复用本模块**，不要另写一份：登录页调
 * {@link login}，成功后用 `setTokens(...)` 落本地缓存（见 `src/store/token.js`）。
 *
 * <p>登录 / 注册 / 刷新都没有 token，一律传 `auth: false`，否则会带上过期的 Bearer 头。
 */

import { get, post } from '@/api/request.js'

/**
 * 账号密码登录。
 *
 * @param {Object} payload
 * @param {string} payload.account 手机号或用户名
 * @param {string} payload.password 明文密码（服务端 BCrypt 校验）
 * @returns {Promise<{token: string, refreshToken: string, userId: string, role: string, expiresIn: number}>}
 */
export function login({ account, password }) {
  // noAuthRetry：凭证接口自身不该走"续期重试"（登录失败只有 40102，重试没有意义）
  // silent：登录页要自己把 40102 渲染成表单内的红字提示，请求层再 toast 一次会重复
  return post(
    '/auth/login',
    { account, password },
    { auth: false, silent: true, noAuthRetry: true },
  )
}

/**
 * 账号密码注册（监护人可用它为老人代建账号）。
 *
 * @param {Object} payload
 * @param {string} payload.phone 手机号（UNIQUE）
 * @param {string} payload.password 明文密码
 * @param {'ELDER'|'GUARDIAN'} payload.role 老人 / 监护人（见 06 的 2.1 `user.role`）；
 *   **护理 / 医生不是这里的值**，它们是 `guardian_relation.role`（FAMILY / DOCTOR / NURSE），
 *   注册页因此只给两个选项
 * @param {string} [payload.username] 用户名（可空，手机号亦可直接登录）
 * @param {string} [payload.name] 姓名
 * @returns {Promise<Object>} 同登录响应
 */
export function register(payload) {
  return post(
    '/auth/register',
    payload,
    { auth: false, silent: true, noAuthRetry: true },
  )
}

/**
 * 刷新 Token（refresh token 轮换：换新即作废旧的）。
 *
 * <p>`feat/mp-auth` 在 `setUnauthorizedHandler` 里调它；失败（40101 / 40102）时必须清本地
 * 凭据并跳登录，不能无限重试。
 *
 * <p>**两个必须的选项**：`auth: false`（没有可用 token，带上过期的 Bearer 反而干扰后端判定）
 * 与 **`noAuthRetry: true`**（否则 refresh 自己返回 40101 会再触发一次刷新 → 递归）；
 * `silent: true` 是因为续期失败后的提示由 mp-auth 统一给，避免重复 toast。
 *
 * @param {string} [refreshToken] 不传则取本地缓存的 refresh token
 * @returns {Promise<{token: string, refreshToken: string, expiresIn: number}>} 轮换出的新 token 对
 */
export function refresh(refreshToken) {
  return post(
    '/auth/refresh',
    { refreshToken },
    { auth: false, silent: true, noAuthRetry: true },
  )
}

/**
 * 当前用户资料与角色（需鉴权）。
 *
 * @returns {Promise<Object>} 含 `userId` / `role`，供前端按角色切换可见入口（01 第 3 章）
 */
export function getMe() {
  return get('/users/me')
}

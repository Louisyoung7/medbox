/**
 * 登录态编排层（`feat/mp-auth`，对应 medbox-spec/08 地基第 3 项）。
 *
 * <p>**分层**：`store/token.js`（凭据存储，唯一读写）← 本文件（编排：登录 / 注册 / 登出 /
 * 续期 / 401 接管）→ 页面。**页面只调本文件的函数**，不要自己拼 `setTokens` + `getMe`，
 * 否则会出现"token 在、用户资料不在"的半登录态。
 *
 * <p>**过期自动刷新怎么工作的**（配合 `src/api/request.js`）：
 * ① 业务请求拿到 `40101` → request 层把错误交给这里的 {@link handleUnauthorized}；
 * ② 它调 `POST /auth/refresh`（并发共享同一个 Promise，只刷一次）；
 * ③ 成功 → 新 token 写入 `store/token.js` → 返回 `true`，request 层**自动重发原请求**，
 * 调用方完全无感知；④ 失败（refresh 也过期 / 网络不通）→ 返回 `false`，request 层清凭据并
 * `reLaunch` 到 {@link LOGIN_PATH}。
 *
 * ```js
 * import { login, logout, isLoggedIn } from '@/store/auth.js'
 * await login({ account: '13800001234', password: '******' })
 * ```
 */

import { CODE, LOGIN_PATH, setUnauthorizedHandler } from '@/api/request.js'
import { getMe, login as loginApi, refresh as refreshApi, register as registerApi } from '@/api/auth.js'
import { clearTokens, getRefreshToken, hasToken, setTokens } from '@/store/token.js'
import { clearUserInfo, getUserInfo, setUserInfo } from '@/store/user.js'
import { installAuthGuard, safeTarget } from '@/store/guard.js'

export { HOME_PATH } from '@/store/guard.js'

/** 正在进行的刷新 Promise：并发的多个 40101 共享同一次刷新（refresh 是轮换制，并发会互相作废）。 */
let refreshing = null

/**
 * 应用启动时调一次（`App.vue` 的 `onLaunch`）：注入 401 接管 → 装路由守卫 → 静默校验登录态。
 *
 * @returns {Promise<boolean>} 本地登录态是否有效
 */
export async function setupAuth() {
  setUnauthorizedHandler(handleUnauthorized)
  installAuthGuard()
  return restoreSession()
}

/**
 * 40101 处理器：交给 request 层调用。
 *
 * @param {ApiError} error 40101 错误（未使用：续期不关心是哪个接口触发的）
 * @returns {Promise<boolean>} true 表示已续期（request 层会重发原请求）
 */
export async function handleUnauthorized(error) {
  const renewed = await refreshAuth()
  if (!renewed) {
    // 提示与跳登录由 request 层统一做（它才知道请求是不是 silent），这里不重复 toast
    console.warn('[auth] 续期失败，登录态已清除', error && error.traceId)
  }
  return renewed
}

/**
 * 用 refresh token 换一对新 token（**并发安全**：同一时刻只存在一个刷新请求）。
 *
 * <p>refresh 采用**轮换**：响应里的新 refresh token 会覆盖旧的；后端没返回新的则沿用旧的
 * （兼容将来"只轮换 access"的实现）。
 *
 * @returns {Promise<boolean>} 是否续期成功
 */
export function refreshAuth() {
  if (refreshing) {
    return refreshing
  }
  const oldRefreshToken = getRefreshToken()
  if (!oldRefreshToken) {
    clearSession()
    return Promise.resolve(false)
  }
  refreshing = (async () => {
    try {
      const data = await refreshApi(oldRefreshToken)
      if (!data || !data.token) {
        throw new Error('refresh 响应缺少 token')
      }
      setTokens({ token: data.token, refreshToken: data.refreshToken || oldRefreshToken })
      return true
    } catch (err) {
      clearSession()
      console.warn('[auth] refresh 失败，已清除本地凭据', err && err.code ? err.code : err)
      return false
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

/**
 * 账号密码登录：写 token → 拉 `GET /users/me` 补全资料。
 *
 * @param {Object} payload
 * @param {string} payload.account 手机号或用户名
 * @param {string} payload.password 明文密码（局域网阶段明文传，服务端 BCrypt 校验）
 * @returns {Promise<Object>} 当前用户资料（`{ userId, role, name, phone }`）
 */
export async function login({ account, password }) {
  const data = await loginApi({ account, password })
  await applySession(data)
  return getUserInfo()
}

/**
 * 注册并直接登录（响应与登录同构，见 02 第 3 章）。监护人可用它为老人代建账号。
 *
 * @param {Object} payload 见 `src/api/auth.js` 的 {@link register}
 * @returns {Promise<Object>} 当前用户资料
 */
export async function register(payload) {
  const data = await registerApi(payload)
  await applySession(data)
  return getUserInfo()
}

/** 退出登录：清凭据与用户资料 → 跳登录页。 */
export function logout() {
  clearSession()
  uni.reLaunch({ url: LOGIN_PATH })
}

/**
 * 启动 / 前台切换时校验本地登录态：有 token 就拉一次 `GET /users/me`。
 *
 * <p>顺带完成**冷启动续期** —— 隔天打开时 access token（2 小时）往往已过期，而 refresh token
 * （7 天）仍有效，这时的 40101 会被 request 层自动续期并重发，用户完全无感。
 *
 * @returns {Promise<boolean>} 登录态是否有效
 */
export async function restoreSession() {
  if (!hasToken()) {
    return false
  }
  try {
    setUserInfo(await getMe())
    return true
  } catch (err) {
    // 40101：request 层已尝试续期，仍失败说明 refresh 也过期（凭据已由它清掉）
    if (err && err.code === CODE.UNAUTHORIZED) {
      clearSession()
      return false
    }
    // 网络不通 / 5xx：不清登录态（可能只是没连上局域网），下次请求再试
    console.warn('[auth] 启动校验失败，保留本地登录态', err && err.code ? err.code : err)
    return false
  }
}

/** 是否已有 access token（页面判断登录态用，等价于 `store/token.js` 的 `hasToken`）。 */
export function isLoggedIn() {
  return hasToken()
}

/**
 * 登录后跳转：优先回跳守卫带过来的 `redirect`，否则去首页。
 *
 * @param {string} [redirect] 守卫带到登录页的回跳路径（只接受 `pages/` 开头的站内路径）
 */
export function navigateAfterLogin(redirect) {
  uni.reLaunch({ url: safeTarget(redirect) })
}

/**
 * 把登录 / 注册响应落成"已登录"状态：写 token → 写用户资料。
 *
 * <p>登录响应本身已带 `userId` / `role`，先落一份兜底再拉 `GET /users/me` 补全 `name` 等字段；
 * `getMe` 失败**不影响登录成功**（token 已到手），只记日志。
 *
 * @param {Object} data 登录 / 注册响应的 `data`
 */
async function applySession(data) {
  if (!data || !data.token) {
    throw new Error('登录响应缺少 token')
  }
  setTokens({ token: data.token, refreshToken: data.refreshToken })
  setUserInfo({
    userId: data.userId,
    role: data.role,
    // getMe 失败时至少有个可展示的名字
    name: data.name,
  })
  try {
    const me = await getMe()
    // 后端字段缺失时用登录响应的值兜底（后端 /users/me 尚未实现，避免整份覆盖成 undefined）
    setUserInfo({
      ...me,
      userId: (me && me.userId) || data.userId,
      role: (me && me.role) || data.role,
      name: (me && me.name) || data.name,
    })
  } catch (err) {
    console.warn('[auth] 拉取用户资料失败，沿用登录响应的角色', err && err.code ? err.code : err)
  }
}

/** 清登录态：凭据与用户资料一起清（二者必须成对，避免半登录态）。 */
function clearSession() {
  clearTokens()
  clearUserInfo()
}

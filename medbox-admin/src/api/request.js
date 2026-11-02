import axios from 'axios'
import { BASE_URL } from '../config'
import { getToken, clearAuth } from '../stores/token'

// 错误码 → 中文提示（复用 medbox-spec/02 第 1.2 章封闭集合；新码需同步 02 与后端 ErrorCode）。
export const ERROR_MESSAGES = {
  40902: '活跃账号不可清理',
  40301: '无权限：需要 ADMIN 角色',
  40401: '用户不存在',
  40102: '登录失败：账号或密码错误',
  40101: '登录已过期，请重新登录',
}

// 业务错误归一化（纯函数，便于单测直接验证错误码映射）。
export function toBusinessError(code, message) {
  return { code, message: message || ERROR_MESSAGES[code] || '请求失败' }
}

// 会话失效判定（后端 40101/40301，或 HTTP 401/403 状态）。
// 注意：后端 401/403 走 HTTP 401/403（非 2xx），axios 默认会落到响应**错误**分支，
// 不能只靠成功分支的 body.code 判断；但 HTTP 401 同时承载 40102（登录失败，本就停在登录页），
// 故「有业务码时以业务码为准」，仅当无业务码（如网关 / CORS 直返 401/403）才按 HTTP 状态兜底。
// 40102 不算会话失效，不在此兜底。
function isAuthFailure(code, status) {
  if (code === 40101 || code === 40301) return true
  if (typeof code === 'number') return false // 有明确业务码但不是会话失效 → 不兜底
  // 无业务码（如网关 / CORS 直接 401/403）→ 按 HTTP 状态兜底
  return status === 401 || status === 403
}

// 会话失效 → 清凭据并回登录页（成功分支与错误分支共用，避免重复）。
function handleAuthFailure(code, status) {
  if (!isAuthFailure(code, status)) return
  clearAuth()
  if (typeof location !== 'undefined' && location.pathname !== '/login') {
    location.href = '/login'
  }
}

const instance = axios.create({ baseURL: BASE_URL, timeout: 15000 })

instance.interceptors.request.use((cfg) => {
  const token = getToken()
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

// 响应拦截：解包 R<T>（成功返回 body.data），失败 reject 归一化错误。
// 40101 / 40301 视为会话失效 → 清凭据并回登录页（见 handleAuthFailure，错误分支也会触发）。
instance.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      const code = body.code
      handleAuthFailure(code) // 成功分支：HTTP 2xx 但业务码为会话失效（如某些接口返回 40101）
      return Promise.reject(toBusinessError(code, body.message))
    }
    return body?.data
  },
  (err) => {
    // 错误分支：axios 对 HTTP 非 2xx（如后端 401/403）走这里，必须兜底会话失效。
    // 网络层失败（未到服务端）也落这里，但无 response，isAuthFailure 自然不命中，原样 reject。
    const resp = err?.response
    const code = resp?.data?.code
    const status = resp?.status
    handleAuthFailure(code, status)
    return Promise.reject(err)
  },
)

export default instance

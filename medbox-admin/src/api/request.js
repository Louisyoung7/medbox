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

const instance = axios.create({ baseURL: BASE_URL, timeout: 15000 })

instance.interceptors.request.use((cfg) => {
  const token = getToken()
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

// 响应拦截：解包 R<T>（成功返回 body.data），失败 reject 归一化错误。
// 40101 / 40301 视为会话失效 → 清凭据并回登录页。
instance.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      const code = body.code
      if (code === 40101 || code === 40301) {
        clearAuth()
        if (typeof location !== 'undefined' && location.pathname !== '/login') {
          location.href = '/login'
        }
      }
      return Promise.reject(toBusinessError(code, body.message))
    }
    return body?.data
  },
  (err) => {
    // 网络层失败（未到服务端）：交给调用方统一提示（不在此处 toast，避免重复）。
    return Promise.reject(err)
  },
)

export default instance

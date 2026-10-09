/**
 * 统一请求出口（对应 medbox-spec/08 的 `feat/mp-request`）。
 *
 * <p>**页面 / 业务模块一律走这里，不要直接调 `uni.request`**：自动拼 `BASE_URL`、注入
 * `Authorization: Bearer` 与 `X-Request-Id`、按 02 的 1.1 拆 `code/message/data`、按 02 的 1.2
 * 分流错误码、统一 loading 与网络兜底。
 *
 * <p>成功兑现业务 `data`（不是整个响应体），失败拒绝为 {@link ApiError}：
 *
 * ```js
 * import { get, post, CODE } from '@/api/request.js'
 *
 * try {
 *   const me = await get('/users/me')
 * } catch (err) {
 *   if (err.code === CODE.GUARDIAN_PENDING) { /* 监护未生效，页面自绘提示 *\/ }
 * }
 * ```
 */

import { BASE_URL } from '@/config/index.js'
import { getAccessToken, clearTokens } from '@/store/token.js'
import { newRequestId } from '@/utils/uuid.js'
import {
  ApiError,
  CODE,
  NETWORK_ERROR_CODE,
  NETWORK_ERROR_MESSAGE,
  messageOf,
} from '@/utils/error.js'

// 业务模块要按 code 细分错误（如 40901 设备离线），从本模块一并导出，省得再引 utils
export { CODE, ApiError, NETWORK_ERROR_CODE } from '@/utils/error.js'

/**
 * 登录页路径：40101 时 `reLaunch` 到这里。
 *
 * <p>**页面本身由 `feat/mp-auth` 建**（本分支只约定路径，不建页），改路径必须同步改 mp-auth。
 */
export const LOGIN_PATH = '/pages/auth/login'

/** 默认超时（毫秒）。局域网内正常响应都是毫秒级，10s 足以覆盖后端首次编译 / 冷启动。 */
const DEFAULT_TIMEOUT = 10000

/** 需要幂等 / 需要链路追踪的写方法：`X-Request-Id` 只给它们注入。 */
const WRITE_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE']

/** loading 并发计数：只在 0→1 时 show、1→0 时 hide，避免并发请求互相提前关掉遮罩。 */
let loadingCount = 0

/** 40101 跳登录的重入保护：并发多个请求都 401 时只跳一次。 */
let redirecting = false

/**
 * 未登录处理器。**`feat/mp-auth` 用它接管 40101**（先尝试 refresh 再决定跳不跳登录），
 * 这样接入刷新逻辑时不必改本文件。
 *
 * @type {?((error: ApiError) => void)}
 */
let unauthorizedHandler = null

function showLoading(title) {
  if (loadingCount === 0) {
    uni.showLoading({ title: title || '加载中', mask: true })
  }
  loadingCount += 1
}

function hideLoading() {
  loadingCount = Math.max(0, loadingCount - 1)
  if (loadingCount === 0) {
    uni.hideLoading()
  }
}

/**
 * 注入未登录处理器（覆盖默认的"清 token + 跳登录页"）。
 *
 * @param {?((error: ApiError) => void)} handler 传 null 恢复默认行为
 * @returns {?Function} 上一个处理器，便于链式恢复
 */
export function setUnauthorizedHandler(handler) {
  const previous = unauthorizedHandler
  unauthorizedHandler = typeof handler === 'function' ? handler : null
  return previous
}

/**
 * 拼 query 串；`null` / `undefined` / 空串一律丢弃（后端分页参数有默认值，传空反而出错）。
 *
 * @param {Object} [params]
 * @returns {string}
 */
function stringify(params) {
  if (!params) {
    return ''
  }
  return Object.keys(params)
    .filter((key) => {
      const value = params[key]
      return value !== null && value !== undefined && value !== ''
    })
    .map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`)
    .join('&')
}

/**
 * 拼完整 URL：已是 `http(s)://` 开头的不拼 `BASE_URL`（便于将来接第三方地址）。
 *
 * @param {string} url 相对路径，如 `/users/me`
 * @param {Object} [params]
 * @returns {string}
 */
function buildUrl(url, params) {
  const full = /^https?:\/\//i.test(url)
    ? url
    : `${BASE_URL}${url.startsWith('/') ? url : `/${url}`}`
  const query = stringify(params)
  if (!query) {
    return full
  }
  return `${full}${full.includes('?') ? '&' : '?'}${query}`
}

/** 统一提示：登录失效有专属分支，其余码走同一出口。 */
function handleError(error, silent) {
  // 只打 code / traceId / 路径，**不打请求体**（登录接口含密码等敏感字段）
  console.warn(
    `[request] ${error.method || ''} ${error.url || ''} code=${error.code} ` +
      `http=${error.httpStatus || '-'} traceId=${error.traceId || '-'} ${error.message}`,
  )

  if (error.isUnauthorized()) {
    handleUnauthorized(error, silent)
    return
  }
  if (!silent) {
    uni.showToast({ title: error.message, icon: 'none', duration: 2000 })
  }
}

/**
 * 40101：清本地凭据 → 跳登录页。
 *
 * <p>若已被 `setUnauthorizedHandler` 接管（mp-auth 的 refresh 逻辑），则完全交给它处理，
 * 本函数不再跳页 —— 刷新成功后重试请求由处理器自己负责。
 */
function handleUnauthorized(error, silent) {
  clearTokens()

  if (unauthorizedHandler) {
    try {
      unauthorizedHandler(error)
    } catch (e) {
      console.error('[request] 未登录处理器抛异常，回退默认跳转', e)
      redirectToLogin(error, silent)
    }
    return
  }
  redirectToLogin(error, silent)
}

function redirectToLogin(error, silent) {
  if (!silent) {
    uni.showToast({ title: error.message, icon: 'none', duration: 2000 })
  }
  if (redirecting) {
    return
  }
  redirecting = true
  uni.reLaunch({
    url: LOGIN_PATH,
    // 登录页尚未落地（mp-auth）时 reLaunch 会失败，complete 里复位避免后续请求永远不跳
    complete: () => {
      redirecting = false
    },
  })
}

/**
 * 发起请求。
 *
 * @typedef {Object} RequestOptions
 * @property {string} url 相对路径（如 `/users/me`）；`http(s)://` 开头则不拼 BASE_URL
 * @property {string} [method='GET']
 * @property {Object|string} [data] 请求体
 * @property {Object} [params] query 参数
 * @property {Object} [header] 额外请求头（会覆盖默认 Content-Type）
 * @property {boolean} [auth=true] 是否注入 `Authorization: Bearer`；登录 / 注册传 false
 * @property {boolean|string} [loading=false] true 或自定义文案时显示遮罩
 * @property {boolean} [silent=false] true 时不自动 toast（页面自绘错误时用）
 * @property {number} [timeout=10000]
 * @property {string} [requestId] 自定义 `X-Request-Id`（一般不传，写操作自动生成）
 *
 * @param {RequestOptions} options
 * @returns {Promise<any>} 成功兑现 `data`（无 data 时为 null），失败拒绝 {@link ApiError}
 */
export function request(options) {
  const {
    url,
    method = 'GET',
    data,
    params,
    header,
    auth = true,
    loading = false,
    silent = false,
    timeout = DEFAULT_TIMEOUT,
    requestId,
  } = options || {}

  const upperMethod = String(method).toUpperCase()
  const fullUrl = buildUrl(url, params)

  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    ...(header || {}),
  }
  if (auth) {
    const token = getAccessToken()
    if (token) {
      headers.Authorization = `Bearer ${token}`
    }
  }
  if (WRITE_METHODS.includes(upperMethod)) {
    headers['X-Request-Id'] = requestId || newRequestId()
  }

  if (loading) {
    showLoading(typeof loading === 'string' ? loading : '')
  }

  return new Promise((resolve, reject) => {
    uni.request({
      url: fullUrl,
      method: upperMethod,
      data: data === undefined ? undefined : data,
      header: headers,
      timeout,
      dataType: 'json',
      success: (res) => {
        if (loading) {
          hideLoading()
        }
        const body = res.data
        // 统一响应（02 的 1.1）：只认数字型 code；data 缺失按 null 处理 ——
        // 后端全局 non_null 会省略 null 字段，**不能用 data === undefined 判异常**
        if (body && typeof body === 'object' && typeof body.code === 'number') {
          if (body.code === CODE.SUCCESS) {
            resolve(body.data === undefined ? null : body.data)
            return
          }
          const error = new ApiError({
            code: body.code,
            message: body.message || messageOf(body.code),
            traceId: body.traceId,
            httpStatus: res.statusCode,
            url: fullUrl,
            method: upperMethod,
          })
          handleError(error, silent)
          reject(error)
          return
        }
        // 非统一结构：/medbox/actuator/** 的 Spring 原生格式、HTML 错误页等
        const error = new ApiError({
          code: CODE.INTERNAL_ERROR,
          message: messageOf(CODE.INTERNAL_ERROR),
          httpStatus: res.statusCode,
          url: fullUrl,
          method: upperMethod,
        })
        handleError(error, silent)
        reject(error)
      },
      fail: (err) => {
        if (loading) {
          hideLoading()
        }
        // 没到服务端：DNS 失败 / 断网 / 超时，用统一的局域网排查文案
        const error = new ApiError({
          code: NETWORK_ERROR_CODE,
          message: NETWORK_ERROR_MESSAGE,
          url: fullUrl,
          method: upperMethod,
        })
        handleError(error, silent)
        reject(error)
      },
    })
  })
}

/**
 * @param {string} url
 * @param {Object} [params]
 * @param {RequestOptions} [options]
 * @returns {Promise<any>}
 */
export const get = (url, params, options) =>
  request({ ...options, url, params, method: 'GET' })

/**
 * @param {string} url
 * @param {Object} [data]
 * @param {RequestOptions} [options]
 * @returns {Promise<any>}
 */
export const post = (url, data, options) =>
  request({ ...options, url, data, method: 'POST' })

/**
 * @param {string} url
 * @param {Object} [data]
 * @param {RequestOptions} [options]
 * @returns {Promise<any>}
 */
export const put = (url, data, options) =>
  request({ ...options, url, data, method: 'PUT' })

/**
 * @param {string} url
 * @param {Object} [data]
 * @param {RequestOptions} [options]
 * @returns {Promise<any>}
 */
export const del = (url, data, options) =>
  request({ ...options, url, data, method: 'DELETE' })

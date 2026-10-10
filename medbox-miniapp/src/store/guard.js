/**
 * 路由守卫（`feat/mp-auth`）：未登录时拦截跳转并送去登录页。
 *
 * <p>uni-app 没有框架级路由守卫，官方方案是 `uni.addInterceptor` —— 在 `App.vue` 的
 * `onLaunch` 里经 `setupAuth()` 装一次，之后**所有** `navigateTo` / `redirectTo` /
 * `reLaunch` / `switchTab` 都会先过这里。
 *
 * <p>**放行条件**：目标在 {@link WHITE_LIST}（登录页 / 注册页）内，或本地已有 access token。
 * 其余情况改跳登录页，并把原目标塞进 `redirect` 参数 —— 登录成功后由
 * `src/store/auth.js` 的 `navigateAfterLogin(redirect)` 回跳。
 *
 * <p>**注意**：这里只做"有没有 token"的**同步**判断（token 有效性由请求层的 40101 续期负责），
 * 因此不能用异步接口，也不会误伤刚启动还没校验完的场景。
 */

import { LOGIN_PATH } from '@/api/request.js'
import { hasToken } from '@/store/token.js'

/** 登录成功后的默认落地页（后续由 `feat/mp-ui-kit` 的 TabBar 首页取代）。 */
export const HOME_PATH = '/pages/index/index'

/** 注册页路径（与登录页一样，未登录也要能进）。 */
export const REGISTER_PATH = '/pages/auth/register'

/** 白名单：未登录也能进的页面。 */
export const WHITE_LIST = [LOGIN_PATH, REGISTER_PATH]

/** 被拦截的跳转方法（小程序里就这四个会换页）。 */
const GUARDED_METHODS = ['navigateTo', 'redirectTo', 'reLaunch', 'switchTab']

/** 幂等标记：多次调 `installAuthGuard` 只装一次。 */
let installed = false

/**
 * 装路由守卫（幂等）。
 *
 * @returns {boolean} 是否由本次调用装上（已装过返回 false）
 */
export function installAuthGuard() {
  if (installed) {
    return false
  }
  installed = true
  GUARDED_METHODS.forEach((method) => {
    uni.addInterceptor(method, {
      invoke(args) {
        const target = normalizePath(args && args.url)
        if (!target || isWhiteListed(target) || hasToken()) {
          return args
        }
        // 未登录：阻断原跳转，改去登录页并带上回跳（登录页在白名单里，不会递归）
        uni.redirectTo({ url: `${LOGIN_PATH}?redirect=${encodeURIComponent(target)}` })
        return false // 返回 false 即阻断原跳转（uni-app 拦截器约定）
      },
    })
  })
  return true
}

/**
 * 路径归一化：去掉 query、补前导斜杠，便于与白名单比较。
 *
 * @param {string} [url]
 * @returns {string} 非法输入返回空串
 */
export function normalizePath(url) {
  if (typeof url !== 'string' || !url) {
    return ''
  }
  const path = url.split('?')[0]
  if (!path) {
    return ''
  }
  return path.startsWith('/') ? path : `/${path}`
}

/**
 * 是否是白名单页面（兼容 `/pages/auth/login`、`pages/auth/login` 与带 query 三种写法）。
 *
 * @param {string} url
 * @returns {boolean}
 */
export function isWhiteListed(url) {
  const target = normalizePath(url)
  return WHITE_LIST.some((item) => normalizePath(item) === target)
}

/**
 * 登录成功后的回跳目标：**只接受 `pages/` 开头的站内路径**，其余一律回首页。
 *
 * <p>`redirect` 来自 URL 参数（可被构造），不做白名单会被拿去开放重定向。
 *
 * @param {string} [redirect] 登录页收到的回跳参数（可能已 URL 编码）
 * @returns {string} 可直接交给 `uni.reLaunch` 的路径
 */
export function safeTarget(redirect) {
  if (!redirect) {
    return HOME_PATH
  }
  let decoded = redirect
  try {
    decoded = decodeURIComponent(redirect)
  } catch (e) {
    decoded = redirect
  }
  const path = normalizePath(decoded)
  if (!/^\/?pages\//.test(path) || isWhiteListed(path)) {
    return HOME_PATH
  }
  // 保留原 query（如 /pages/device/detail?id=BOXA1001）
  return decoded.startsWith('/') ? decoded : `/${decoded}`
}

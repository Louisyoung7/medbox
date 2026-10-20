/**
 * 统一配置出口（见 medbox-spec/01 的 4.2）
 *
 * uni-app 没有框架级配置文件，因此自建本模块：
 * 业务代码一律从这里取后端地址，**禁止在页面 / api 里写死 IP**。
 */

const STORAGE_KEY = 'serverHost'
const PORT = '8080'

// 编译期默认值：来自 .env / .env.development（入库）与 .env.local（不入库，各人填自己的 IP）
//
// 兜底值取 **127.0.0.1**（回环）：不是一个"碰巧能连上"的别人家的内网 IP。
// 它的语义是"后端就跑在这台机器上"（微信开发者工具 / H5 都属于同一台机器），
// 落到别的网段也不会误连。真机型场景必须显式配：写 .env.local 后重编译，
// 或由设置页写本地缓存 `serverHost`（见 {@link setServerHost}）。
const DEFAULT_HOST = import.meta.env.VITE_SERVER_HOST || '127.0.0.1'
// 运行时覆盖：真机调试时 IP 常变，改一次不必重新编译（下次启动生效）
const HOST = uni.getStorageSync(STORAGE_KEY) || DEFAULT_HOST
// VITE_TLS 写在入库的 .env（不是 .env.development）：后者只在 development 模式加载，
// `uni build -p mp-weixin`（production 模式）读不到，"上线切 https / wss"会静默失效。
const TLS = import.meta.env.VITE_TLS === 'true'

export const SERVER_HOST = HOST

/**
 * 当前编译平台（`process.env.UNI_PLATFORM` 由 uni-app 注入，`uni -p xxx` / `uni build -p xxx`
 * 时静态替换为 `'mp-weixin'` / `'h5'` / `'app'` 等）。
 *
 * <p>**只用来做平台隔离，不要用它写业务逻辑**——非 uni 环境（如 Vitest）读不到它为 `''`。
 */
const PLATFORM = process.env.UNI_PLATFORM || ''

/**
 * 是否 H5。**只有 H5 才认 `VITE_BASE_URL`**（同源相对路径 + dev server 反代）。
 *
 * <p>小程序 / App 的产物里带着同一个编译期常量，若一起生效，`BASE_URL` 会变成
 * 相对路径 `/medbox/api/v1`，而它们的 origin 是各自的设备而不是你的电脑 ——
 * 请求必然打空，报错还是"网络异常"，和 IP 填错一模一样，极难排查。
 */
const IS_H5 = PLATFORM === 'h5'

/**
 * REST 前缀。默认拼成绝对地址；**H5 浏览器联调**时可在不入库的 `.env.local` 里写
 * `VITE_BASE_URL=/medbox/api/v1`（同源相对路径），由 Vite dev server 反代到后端，
 * 绕开浏览器的跨域预检（后端 REST 未开 CORS，见 README「浏览器联调」）。
 *
 * <p>**平台隔离（2026-10-20）**：该 override **只在 H5 生效**，小程序 / App 一律忽略 ——
 * 否则配它会把小程序也带沟里（见 {@link IS_H5}）。因此它可以安全地写在 `.env.local` 里，
 * 一份配置同时跑 H5 与微信小程序。
 */
const BASE_URL_OVERRIDE = IS_H5 ? import.meta.env.VITE_BASE_URL : ''

if (import.meta.env.VITE_BASE_URL && !IS_H5) {
  // 配了却不在 H5：静默忽略会让人以为"配了没生效"，明确说一句
  console.warn(
    `[config] VITE_BASE_URL 只在 H5 生效，当前平台 ${PLATFORM || '未知'} 已忽略；` +
      `小程序 / App 请用 VITE_SERVER_HOST 指向后端地址`,
  )
}

export const BASE_URL = BASE_URL_OVERRIDE || `${TLS ? 'https' : 'http'}://${HOST}:${PORT}/medbox/api/v1`
export const WS_URL = `${TLS ? 'wss' : 'ws'}://${HOST}:${PORT}/medbox/ws`
// 没有 MQTT_URL：小程序不直连 MQTT，设备侧消息一律由后端中转

/**
 * 把用户输入的"服务器地址"归一化成**裸 HOST**（无协议、无端口、无路径）。
 *
 * <p>设置页让用户填的往往是浏览器地址栏里那一串（`http://192.168.1.5:8080/medbox/`），
 * 而端口在本模块里固定为 `8080`（见 {@link PORT}）。不归一化就会拼出
 * `http://http://192.168.1.5:8080:8080/medbox/api/v1` 这种地址，错误现象还很像"后端没起"。
 *
 * @param {string} raw 用户输入，容忍前后空格与全角冒号等常见输入噪声
 * @returns {string} 归一化后的 HOST；输入为空 / 非字符串时返回空串
 */
export function normalizeHost(raw) {
  if (typeof raw !== 'string') {
    return ''
  }
  let host = raw.trim().replace(/^\/+/, '')
  // 去协议：`http://` / `https://`（含漏敲斜杠的 `http:192.168.1.5`）
  host = host.replace(/^https?:\/*/i, '')
  // 去路径 / query：`192.168.1.5/medbox?a=1` → `192.168.1.5`
  host = host.split(/[/?#]/)[0]
  // 去端口（PORT 固定 8080，不允许 HOST 再带一个）：全角冒号也一并处理
  host = host.replace(/[：:]\d+$/, '')
  return host.trim()
}

/**
 * 覆盖后端地址并写入本地缓存（"服务器地址"设置页使用）。
 *
 * <p>**写之前先归一化**（见 {@link normalizeHost}），容忍用户粘贴带协议 / 端口 / 路径的地址。
 * 非法输入（空串 / 非字符串）**不写入缓存**，返回 `false`，调用方据此提示用户重填 ——
 * 写空串会在下一次启动时被 {@link HOST} 的 `||` 静默忽略，看起来"改了没生效"。
 *
 * <p>**下次启动才生效**：`BASE_URL` / `WS_URL` 在模块加载时算好，当前会话不会变。
 *
 * @param {string} host 局域网 IP 或域名，允许带 `http://` 与端口（会被剥掉）
 * @returns {boolean} 是否写入成功
 */
export function setServerHost(host) {
  const normalized = normalizeHost(host)
  if (!normalized) {
    return false
  }
  uni.setStorageSync(STORAGE_KEY, normalized)
  return true
}

/** 清除运行时覆盖，回落到 `.env.local` / `.env` 的编译期默认值 */
export function clearServerHost() {
  uni.removeStorageSync(STORAGE_KEY)
}

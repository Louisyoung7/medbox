/**
 * 统一配置出口（见 medbox-spec/01 的 4.2）
 *
 * uni-app 没有框架级配置文件，因此自建本模块：
 * 业务代码一律从这里取后端地址，**禁止在页面 / api 里写死 IP**。
 */

const STORAGE_KEY = 'serverHost'
const PORT = '8080'

// 编译期默认值：来自 .env.development（入库）与 .env.local（不入库，各人填自己的 IP）
const DEFAULT_HOST = import.meta.env.VITE_SERVER_HOST || '192.168.1.10'
// 运行时覆盖：真机调试时 IP 常变，改一次不必重新编译（下次启动生效）
const HOST = uni.getStorageSync(STORAGE_KEY) || DEFAULT_HOST
const TLS = import.meta.env.VITE_TLS === 'true'

export const SERVER_HOST = HOST

export const BASE_URL = `${TLS ? 'https' : 'http'}://${HOST}:${PORT}/medbox/api/v1`
export const WS_URL = `${TLS ? 'wss' : 'ws'}://${HOST}:${PORT}/medbox/ws`
// 没有 MQTT_URL：小程序不直连 MQTT，设备侧消息一律由后端中转

/**
 * 覆盖后端地址并写入本地缓存（"服务器地址"设置页使用）
 * @param {string} host 局域网 IP 或域名，不含协议与端口
 */
export function setServerHost(host) {
  uni.setStorageSync(STORAGE_KEY, host)
}

/** 清除运行时覆盖，回落到 .env.local / .env.development 的默认值 */
export function clearServerHost() {
  uni.removeStorageSync(STORAGE_KEY)
}

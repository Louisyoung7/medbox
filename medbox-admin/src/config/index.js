// 统一配置出口（对齐 medbox-miniapp/src/config 思路：业务代码一律从这里取地址，禁止写死 IP）。
//
// 开发期用 .env 的 VITE_BASE_URL（相对路径 /medbox/api/v1），由 Vite dev server 反代到后端 8080，
// 绕开浏览器同源 / CORS 预检（见 medbox-spec/01 第 4 章）。生产/测试直连时改 VITE_BASE_URL 为绝对地址。

const SERVER_HOST = import.meta.env.VITE_SERVER_HOST || '127.0.0.1'
const TLS = import.meta.env.VITE_TLS === 'true'
const PORT = '8080'

export const BASE_URL =
  import.meta.env.VITE_BASE_URL || `${TLS ? 'https' : 'http'}://${SERVER_HOST}:${PORT}/medbox/api/v1`

// 后端地址（供 vite.config.js 的 proxy target 之外的兜底说明，无运行时用途）
export const SERVER_HOST_RESOLVED = SERVER_HOST

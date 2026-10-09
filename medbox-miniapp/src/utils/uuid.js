/**
 * 请求号生成（用于 `X-Request-Id`）。
 *
 * <p>后端 `TraceContext.sanitize` 只接受 `[A-Za-z0-9_-]` 且长度 ≤ 64，不合法就丢弃并自动
 * 生成；这里统一产**32 位小写十六进制**（与后端 `newTraceId()` 的 UUID 去连字符同形），
 * 保证客户端带的号一定被后端沿用 —— 于是小程序日志里的号能直接 grep 到后端同一条请求。
 */

const HEX = '0123456789abcdef'

/**
 * 生成 32 位十六进制字符串。
 *
 * <p>微信小程序运行时没有 `crypto.randomUUID`，优先用 `crypto.getRandomValues`（H5 与新版基础
 * 库可用），不可用时回落到 `Math.random`：请求号只用于链路追踪与幂等去重，不要求密码学强度。
 *
 * @returns {string} 32 位小写十六进制
 */
export function newRequestId() {
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const bytes = crypto.getRandomValues(new Uint8Array(16))
    let out = ''
    for (let i = 0; i < bytes.length; i++) {
      out += HEX[bytes[i] >> 4] + HEX[bytes[i] & 15]
    }
    return out
  }
  let out = ''
  for (let i = 0; i < 32; i++) {
    out += HEX[Math.floor(Math.random() * 16)]
  }
  return out
}

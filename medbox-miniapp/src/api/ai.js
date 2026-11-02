/**
 * AI 洞察层请求出口（对应 medbox-spec/05 第 2 章；feat/mp-ai-insight）。
 *
 * <p>**同步接口**（按需洞察 / 周期洞察）直接走 `request()` —— 自动拼 `BASE_URL`、注入
 * `Authorization: Bearer`、`POST` 自动带 `X-Request-Id`、按 02 的 1.2 分流错误码
 * （50310 AI 不可用 / 40001 参数 / 40301 权限），页面只 `catch` 按 `code` 细分。
 *
 * <p>**流式接口**（长洞察逐字）`request.js` 不支持分块读取，本文件自研跨端封装：
 * H5 用浏览器 `fetch` 的 `ReadableStream`；微信小程序用 `wx.request({enableChunked:true})`
 * + `onChunkReceived` 累加字节后按行解析 JSON 帧。**不引第三方流式库**。两端统一回调
 * `onDelta/onDone/onError`，纯帧逻辑由 `utils/ai.js` 的 {@link applyInsightFrame} 收敛。
 *
 * <p>后端 `feat/ai-insight`（07）当前未落地，按 AGENTS.md 通行做法**按 05 契约 + Mock 先行**，
 * 字段做容错（见 `utils/ai.js` 的 `parseInsight`）。
 */

import { BASE_URL } from '@/config/index.js'
import { getAccessToken } from '@/store/token.js'
import { request } from '@/api/request.js'
import { ApiError, CODE, messageOf } from '@/utils/error.js'
import {
  buildInsightPayload,
  parseInsight,
  applyInsightFrame,
  initialState,
  parseLlmConfig,
} from '@/utils/ai.js'

/** 拼 AI 接口完整 URL（相对 `/ai/*` 走 BASE_URL）。 */
function aiUrl(path) {
  const base = BASE_URL.endsWith('/') ? BASE_URL.slice(0, -1) : BASE_URL
  return `${base}${path.startsWith('/') ? path : `/${path}`}`
}

/** 流式请求的鉴权头（与 `request()` 一致：Bearer，无 X-Request-Id——流式非幂等写）。 */
function streamHeaders() {
  const token = getAccessToken()
  const headers = { 'Content-Type': 'application/json; charset=utf-8' }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  return headers
}

/**
 * 同步生成洞察（降级 / 单测 / 短洞察用）：`POST /ai/insight`。
 *
 * @param {Object} args `{elderId, type, rangeDays, deviceId, now}`
 * @param {Object} [options] 透传 `request`（如 `{silent:true}`）
 * @returns {Promise<Object>} `parseInsight` 后的洞察对象
 * @throws {ApiError} 50310 / 40001 / 40301 / 网络
 */
export function requestInsight(args, options = {}) {
  const payload = buildInsightPayload(args)
  return request({
    url: '/ai/insight',
    method: 'POST',
    data: payload,
    silent: true,
    ...options,
  }).then(parseInsight)
}

/**
 * 查询周期洞察设置：`GET /ai/insight/schedule?elderId=`。
 * @param {{elderId:string}} query
 * @returns {Promise<Object>} `{ elderId, enabled, frequency }`（缺字段容错由页面兜底）
 */
export function getInsightSchedule({ elderId }) {
  return request({
    url: '/ai/insight/schedule',
    method: 'GET',
    params: { elderId },
    silent: true,
  }).then((data) => data || {})
}

/**
 * 调整周期洞察开关与节奏（仅监护人）：`PUT /ai/insight/schedule?elderId=`。
 * @param {{elderId:string, frequency:string}} payload `frequency` 为 `DAILY` / `WEEKLY`
 * @returns {Promise<Object>} 更新后的设置
 */
export function putInsightSchedule({ elderId, frequency }) {
  return request({
    url: '/ai/insight/schedule',
    method: 'PUT',
    params: { elderId },
    data: { frequency },
    silent: true,
  }).then((data) => data || {})
}

/**
 * 查询 LLM 配置（05 第 2.1 章）：`GET /ai/config?elderId=`。
 *
 * <p>**不回显明文 Key**，只给 `apiKeyMask`；老人端无入口且本端不持有明文。
 *
 * @param {{elderId:string}} query
 * @returns {Promise<Object>} `parseLlmConfig` 后的配置（缺字段容错）
 */
export function getLlmConfig({ elderId }) {
  return request({
    url: '/ai/config',
    method: 'GET',
    params: { elderId },
    silent: true,
  }).then((data) => parseLlmConfig(data))
}

/**
 * 保存 LLM 配置（仅监护人）：`PUT /ai/config?elderId=`。
 *
 * @param {{elderId:string, provider:string, apiKey:string, model:string, baseUrl:string, enabled:boolean}} payload
 * @returns {Promise<Object>} 保存后的脱敏配置（`apiKeyMask`，无明文）
 */
export function putLlmConfig(payload) {
  const { elderId, provider, apiKey, model, baseUrl, enabled } = payload || {}
  return request({
    url: '/ai/config',
    method: 'PUT',
    params: { elderId },
    data: { provider, apiKey, model, baseUrl, enabled: enabled !== false },
    silent: true,
  }).then((data) => parseLlmConfig(data))
}

/**
 * 连通测试（仅监护人，不真正出网）：`POST /ai/config/test?elderId=`。
 * @param {{elderId:string}} query
 * @returns {Promise<{ok:boolean, message:string}>}
 */
export function testLlmConfig({ elderId }) {
  return request({
    url: '/ai/config/test',
    method: 'POST',
    params: { elderId },
    silent: true,
  }).then((data) => ({
    ok: !!(data && data.ok),
    message: (data && data.message) || (data && data.ok ? '连通正常' : '连通失败'),
  }))
}

/**
 * 清除 LLM 配置（仅监护人）：`DELETE /ai/config?elderId=`。
 * @param {{elderId:string}} query
 * @returns {Promise<Object>} 统一响应 `data`（通常为空对象）
 */
export function deleteLlmConfig({ elderId }) {
  return request({
    url: '/ai/config',
    method: 'DELETE',
    params: { elderId },
    silent: true,
  }).then((data) => data || {})
}

/**
 * 把一段分块文本拆成完整 JSON 帧（按 `\n` 切分，保留半行待下次拼接）。
 *
 * @param {string} buffer 累计文本
 * @returns {{frames:Array<Object>, rest:string}} 已解析帧 + 未完成的半行
 */
function splitFrames(buffer) {
  const idx = buffer.lastIndexOf('\n')
  if (idx === -1) {
    return { frames: [], rest: buffer }
  }
  const complete = buffer.slice(0, idx)
  const rest = buffer.slice(idx + 1)
  const frames = complete
    .split('\n')
    .map((line) => {
      const t = line.trim()
      if (!t) return null
      try {
        return JSON.parse(t)
      } catch (e) {
        return null
      }
    })
    .filter(Boolean)
  return { frames, rest }
}

/**
 * 把整段文本当"统一错误响应"解析并抛出 {@link ApiError}；解析不出业务码则按网络失败。
 * @param {string} text 非帧文本（如 `{code:50310,...}`）
 */
function throwStreamError(text) {
  let body
  try {
    body = JSON.parse(text.trim())
  } catch (e) {
    body = null
  }
  if (body && typeof body.code === 'number') {
    throw new ApiError({ code: body.code, message: body.message || messageOf(body.code) })
  }
  throw new ApiError({ code: CODE.INTERNAL_ERROR, message: messageOf(CODE.INTERNAL_ERROR) })
}

/** 微信小程序：ArrayBuffer → UTF-8 字符串（不依赖可能缺失的 `TextDecoder`）。 */
function utf8Decode(bytes) {
  let result = ''
  let i = 0
  const len = bytes.length
  while (i < len) {
    const b = bytes[i++]
    if (b < 0x80) {
      result += String.fromCharCode(b)
    } else if (b >= 0xc0 && b < 0xe0) {
      const c = bytes[i++] & 0x3f
      result += String.fromCharCode(((b & 0x1f) << 6) | c)
    } else if (b >= 0xe0 && b < 0xf0) {
      const c = bytes[i++] & 0x3f
      const d = bytes[i++] & 0x3f
      result += String.fromCharCode(((b & 0x0f) << 12) | (c << 6) | d)
    } else {
      const c = bytes[i++] & 0x3f
      const d = bytes[i++] & 0x3f
      const e = bytes[i++] & 0x3f
      let cp = ((b & 0x07) << 18) | (c << 12) | (d << 6) | e
      cp -= 0x10000
      result += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff))
    }
  }
  return result
}

/** H5 传输：浏览器 `fetch` + `ReadableStream` 逐帧分发。 */
function h5Transport(payload, handlers) {
  const { onDelta, onDone, onError, signal } = handlers
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null
  const acSignal = controller ? controller.signal : signal
  ;(async () => {
    let res
    try {
      res = await fetch(aiUrl('/ai/insight/stream'), {
        method: 'POST',
        headers: streamHeaders(),
        body: JSON.stringify(payload),
        signal: acSignal,
      })
    } catch (e) {
      onError(e && e.name === 'AbortError' ? null : new ApiError({ code: -1, message: messageOf(-1) }))
      return
    }
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      try {
        throwStreamError(text)
      } catch (err) {
        onError(err)
      }
      return
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let state = initialState()
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const { frames, rest } = splitFrames(buffer)
        buffer = rest
        for (const fr of frames) {
          if (fr.type === 'delta') {
            state = applyInsightFrame(state, fr)
            onDelta(fr.content || '')
          } else if (fr.type === 'done') {
            state = applyInsightFrame(state, fr)
            onDone(parseInsight(state))
          }
        }
      }
      if (buffer.trim()) {
        throwStreamError(buffer)
      }
    } catch (e) {
      if (e && e.name === 'AbortError') return
      onError(e && e.code ? e : new ApiError({ code: -1, message: messageOf(-1) }))
    }
  })()
  return { abort: () => controller && controller.abort() }
}

/** 微信小程序传输：`wx.request({enableChunked:true})` + `onChunkReceived` 逐帧分发。 */
function mpTransport(payload, handlers) {
  const { onDelta, onDone, onError } = handlers
  if (typeof wx === 'undefined' || typeof wx.request !== 'function') {
    onError(new ApiError({ code: -1, message: messageOf(-1) }))
    return { abort: () => {} }
  }
  let buffer = ''
  let state = initialState()
  const task = wx.request({
    url: aiUrl('/ai/insight/stream'),
    method: 'POST',
    header: streamHeaders(),
    data: payload,
    enableChunked: true,
    success: () => {
      if (buffer.trim()) {
        try {
          throwStreamError(buffer)
        } catch (err) {
          onError(err)
        }
      }
    },
    fail: (err) =>
      onError(new ApiError({ code: -1, message: (err && err.errMsg) || messageOf(-1) })),
  })
  task.onChunkReceived(({ data }) => {
    try {
      buffer += utf8Decode(new Uint8Array(data))
    } catch (e) {
      return
    }
    const { frames, rest } = splitFrames(buffer)
    buffer = rest
    for (const fr of frames) {
      if (fr.type === 'delta') {
        state = applyInsightFrame(state, fr)
        onDelta(fr.content || '')
      } else if (fr.type === 'done') {
        state = applyInsightFrame(state, fr)
        onDone(parseInsight(state))
      }
    }
  })
  return { abort: () => task.abort && task.abort() }
}

/**
 * 流式生成洞察（长洞察逐字）：`POST /ai/insight/stream`。
 *
 * <p>平台自动选择传输实现（H5 `fetch` / 小程序 `wx.request`）；测试可传 `transport` 注入假实现。
 *
 * @param {Object} args `{elderId, type, rangeDays, deviceId, now}`
 * @param {Object} handlers `{ onDelta(content:string), onDone(insight:Object), onError(err), signal? }`
 * @param {Object} [opts] `{ transport }` 仅供单测注入
 * @returns {{abort:Function}} 取消句柄（页面 `onUnload` 调 `abort()`）
 */
export function streamInsight(args, handlers = {}, opts = {}) {
  const payload = buildInsightPayload(args)
  const transport =
    opts.transport ||
    (typeof wx !== 'undefined' && typeof wx.request === 'function' ? mpTransport : h5Transport)
  try {
    return transport(payload, handlers)
  } catch (e) {
    // 同步抛错（如注入的测试 transport）兜底到 onError，避免漏到页面
    if (handlers && handlers.onError) handlers.onError(e)
    return { abort: () => {} }
  }
}

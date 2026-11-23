/**
 * AI 洞察层请求出口（对应 medbox-spec/05 第 2 章；feat/mp-ai-insight）。
 *
 * <p>**同步接口**（按需洞察 / 周期洞察）直接走 `request()` —— 自动拼 `BASE_URL`、注入
 * `Authorization: Bearer`、`POST` 自动带 `X-Request-Id`、按 02 的 1.2 分流错误码
 * （50310 AI 不可用 / 40001 参数 / 40301 权限），页面只 `catch` 按 `code` 细分。
 *
 * <p>**流式接口**（长洞察逐字）`request.js` 不支持分块读取，本文件自研封装：
 * H5 用浏览器 `fetch` 的 `ReadableStream`；App 用 `plus.net.XMLHttpRequest` 的 `readyState=3`
 * 递增 `responseText`。两条链路都把文本切成 JSON 帧，纯帧逻辑由 `utils/ai.js` 的
 * {@link applyInsightFrame} 收敛。传输不可用或首个字到达前失败时，自动降级为同步
 * `POST /ai/insight`（一次性返回）。**不引第三方流式库**。
 *
 * <p>后端已落地，字段按 05 契约对齐；展示层仍做容错（见 `utils/ai.js` 的 `parseInsight`）。
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
 * @returns {Promise<Object>} `{ elderId, enabled, cadence }`（缺字段容错由页面兜底）
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
 * @param {{elderId:string, cadence:string}} payload `cadence` 为 `DAILY` / `WEEKLY`（与后端字段一致）
 * @returns {Promise<Object>} 更新后的设置
 */
export function putInsightSchedule({ elderId, cadence }) {
  return request({
    url: '/ai/insight/schedule',
    method: 'PUT',
    params: { elderId },
    data: { cadence },
    silent: true,
  }).then((data) => data || {})
}

/**
 * 查询 LLM 配置（05 第 2.1 章）：`GET /ai/config?elderId=`。
 *
 * <p>**不回显明文 Key**，只给 `apiKeyMask`；老人端无入口且本端不持有明文。
 *
 * <p>未配置时后端口径不统一：真后端 `feat/ai-insight` 返回 **40401**（`BizAssert.notFound`），
 * 而 Mock 返回 200 空。两者都视作「空配置」，页面展示空表单而非报错。
 *
 * @param {{elderId:string}} query
 * @returns {Promise<Object>} `parseLlmConfig` 后的配置（缺字段容错；40401 → 空配置）
 */
export function getLlmConfig({ elderId }) {
  return request({
    url: '/ai/config',
    method: 'GET',
    params: { elderId },
    silent: true,
  })
    .then((data) => parseLlmConfig(data))
    .catch((err) => {
      // 未配置大模型：真后端 40401 / Mock 200 空 —— 统一为空配置（页面空表单）
      if (err instanceof ApiError && err.code === CODE.NOT_FOUND) return parseLlmConfig(null)
      throw err
    })
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
      // SSE 注释行（如 `:keep-alive`）忽略
      if (t.startsWith(':')) return null
      // 兼容两种后端：真实后端走 SseEmitter 发 `data: {json}`；Mock 发裸 JSON 行
      let payload = t
      if (t.startsWith('data:')) {
        payload = t.slice('data:'.length).trim()
      }
      if (!payload) return null
      try {
        return JSON.parse(payload)
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

/**
 * 流式传输选择：App 有 `plus.net.XMLHttpRequest` 时优先用它（webview 的 fetch 不支持增量读），
 * 否则退浏览器 `fetch`；都没有返回 `null`（调用方走非流式降级）。纯函数，便于单测。
 *
 * @param {{transport?:Function, hasAppXhr?:boolean, hasFetch?:boolean}} [env]
 * @returns {Function|null}
 */
export function resolveStreamTransport(env = {}) {
  if (typeof env.transport === 'function') return env.transport
  if (env.hasAppXhr) return appTransport
  if (env.hasFetch) return h5Transport
  return null
}

/** 运行时是否具备 App 的增量 XHR。 */
function hasAppXhr() {
  return (
    typeof plus !== 'undefined' &&
    plus &&
    plus.net &&
    typeof plus.net.XMLHttpRequest === 'function'
  )
}

/**
 * App 传输：`plus.net.XMLHttpRequest` 在 `readyState=3` 时 `responseText` 递增，
 * 取本次新增片段交给统一帧解析。
 */
function appTransport(payload, handlers) {
  const { onDelta, onDone, onError } = handlers
  let xhr
  try {
    xhr = new plus.net.XMLHttpRequest()
  } catch (e) {
    onError(new ApiError({ code: -1, message: messageOf(-1) }))
    return { abort: () => {} }
  }

  let consumed = 0
  let buffer = ''
  let state = initialState()
  let completed = false
  let deltaCount = 0
  let settled = false

  const fail = (err) => {
    if (settled) return
    settled = true
    onError(err || new ApiError({ code: -1, message: messageOf(-1) }))
  }

  const push = (text) => {
    if (!text) return
    buffer += text
    const { frames, rest } = splitFrames(buffer)
    buffer = rest
    for (const fr of frames) {
      if (fr.type === 'delta') {
        deltaCount += 1
        state = applyInsightFrame(state, fr)
        onDelta(fr.content || '')
      } else if (fr.type === 'done' && !completed) {
        completed = true
        state = applyInsightFrame(state, fr)
        onDone(parseInsight(state))
      }
    }
  }

  const drain = () => {
    const text = xhr.responseText || ''
    if (text.length > consumed) {
      push(text.slice(consumed))
      consumed = text.length
    }
  }

  xhr.onreadystatechange = () => {
    if (settled) return
    if (xhr.readyState === 3) {
      drain()
      return
    }
    if (xhr.readyState !== 4) return
    drain()
    if (xhr.status >= 200 && xhr.status < 300) {
      if (settled) return
      try {
        if (buffer.trim()) throwStreamError(buffer)
      } catch (e) {
        fail(e)
        return
      }
      if (!completed) {
        // 服务端正常结束却没发 done 帧：有内容就按当前状态收尾，没内容视为失败
        if (deltaCount > 0) {
          settled = true
          onDone(parseInsight(state))
        } else {
          fail(new ApiError({ code: -1, message: messageOf(-1) }))
        }
      } else {
        settled = true
      }
      return
    }
    let text = ''
    try {
      text = xhr.responseText || ''
    } catch (e) {
      text = ''
    }
    try {
      throwStreamError(text)
    } catch (err) {
      fail(err)
    }
  }
  xhr.onerror = () => fail(new ApiError({ code: -1, message: messageOf(-1) }))

  xhr.open('POST', aiUrl('/ai/insight/stream'))
  const headers = streamHeaders()
  Object.keys(headers).forEach((k) => xhr.setRequestHeader(k, headers[k]))
  xhr.send(JSON.stringify(payload))

  return {
    abort: () => {
      settled = true
      if (typeof xhr.abort === 'function') xhr.abort()
    },
  }
}

/**
 * 非流式降级：直接 `POST /ai/insight`，一次性把完整洞察交给 `onDone`。
 * 传输不可用、或首个 `delta` 到达前就失败时使用（已输出部分内容则不再降级，避免重复生成）。
 */
function fallbackInsight(args, handlers) {
  let cancelled = false
  requestInsight(args).then(
    (insight) => {
      if (!cancelled && handlers.onDone) handlers.onDone(insight)
    },
    (err) => {
      if (!cancelled && handlers.onError) handlers.onError(err)
    },
  )
  return {
    abort: () => {
      cancelled = true
    },
  }
}

/**
 * 流式生成洞察（长洞察逐字）：`POST /ai/insight/stream`。
 *
 * <p>App 走 `plus.net.XMLHttpRequest`、H5 走 `fetch`；传输不可用或首字前失败时降级为
 * 同步 `requestInsight`。测试可传 `transport` 注入假实现。
 *
 * @param {Object} args `{elderId, type, rangeDays, deviceId, now}`
 * @param {Object} handlers `{ onDelta(content:string), onDone(insight:Object), onError(err), signal? }`
 * @param {Object} [opts] `{ transport }` 仅供单测注入
 * @returns {{abort:Function}} 取消句柄（页面 `onUnload` 调 `abort()`）
 */
export function streamInsight(args, handlers = {}, opts = {}) {
  const payload = buildInsightPayload(args)
  const transport = resolveStreamTransport({
    transport: opts.transport,
    hasAppXhr: hasAppXhr(),
    hasFetch: typeof fetch === 'function',
  })
  if (!transport) {
    return fallbackInsight(args, handlers)
  }

  let produced = false
  const wrapped = {
    ...handlers,
    onDelta: (content) => {
      produced = true
      if (handlers.onDelta) handlers.onDelta(content)
    },
    onError: (err) => {
      // null 表示主动取消，不降级
      if (err === null) return
      if (!produced) {
        fallbackInsight(args, handlers)
        return
      }
      if (handlers.onError) handlers.onError(err)
    },
  }
  try {
    return transport(payload, wrapped)
  } catch (e) {
    // 同步抛错（如注入的测试 transport）兜底，避免漏到页面
    if (!produced) return fallbackInsight(args, handlers)
    if (handlers.onError) handlers.onError(e)
    return { abort: () => {} }
  }
}

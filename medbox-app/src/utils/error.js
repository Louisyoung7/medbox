/**
 * 业务错误码与错误类型（对齐 medbox-spec/02 的 1.2，后端 `ErrorCode` 枚举）。
 *
 * <p>02 的 1.2 是**封闭集合**：新增错误码必须同时改文档 02 与后端 `ErrorCode` 枚举，
 * 三者不一致即视为缺陷。本文件只是前端映射，不定义新业务码。
 */

/** 业务码：成功与各类失败（数值与后端一一对应）。 */
export const CODE = {
  SUCCESS: 0,
  BAD_REQUEST: 40001,
  UNAUTHORIZED: 40101,
  BAD_CREDENTIALS: 40102,
  FORBIDDEN: 40301,
  GUARDIAN_PENDING: 40302,
  NOT_FOUND: 40401,
  CONFLICT: 40901,
  TOO_MANY_REQUESTS: 42901,
  INTERNAL_ERROR: 50000,
  AI_UNAVAILABLE: 50310,
}

/**
 * 客户端本地错误码：**网络失败 / 超时**（`uni.request` 的 fail 回调）。
 *
 * <p>它不是 02 的 1.2 里的业务码（后端没返回任何东西），仅为让调用方用同一个
 * `catch` 处理"根本没到服务端"的情况，避免与 50000 混淆。
 */
export const NETWORK_ERROR_CODE = -1

/** 网络失败统一文案：局域网演示阶段最高频的失败原因就是网段 / IP 不对。 */
export const NETWORK_ERROR_MESSAGE = '网络异常，请检查手机与电脑是否在同一局域网'

/** 各业务码的默认提示（后端没给 message 时用；与后端 `ErrorCode` 的 message 一致）。 */
const CODE_MESSAGE = {
  [CODE.SUCCESS]: 'success',
  [CODE.BAD_REQUEST]: '参数校验失败',
  [CODE.UNAUTHORIZED]: '登录已失效，请重新登录',
  [CODE.BAD_CREDENTIALS]: '账号或密码错误',
  [CODE.FORBIDDEN]: '无权限访问该资源',
  [CODE.GUARDIAN_PENDING]: '监护关系未生效，请等待老人确认',
  [CODE.NOT_FOUND]: '资源不存在',
  [CODE.CONFLICT]: '状态冲突',
  [CODE.TOO_MANY_REQUESTS]: '请求过于频繁，请稍后再试',
  [CODE.INTERNAL_ERROR]: '服务端内部错误',
  [CODE.AI_UNAVAILABLE]: 'AI 服务暂不可用',
}

/**
 * 取错误码的默认提示；未知码一律按 `INTERNAL_ERROR` 处理（与后端 `ErrorCode.of(int)` 一致，
 * 不透出未知码）。
 *
 * @param {number} code 业务码
 * @returns {string} 可读提示
 */
export function messageOf(code) {
  if (code === NETWORK_ERROR_CODE) {
    return NETWORK_ERROR_MESSAGE
  }
  return CODE_MESSAGE[code] || CODE_MESSAGE[CODE.INTERNAL_ERROR]
}

/**
 * 请求失败的错误对象：调用方在 `catch` 里按 `code` 细分处理。
 *
 * <p>例：设备离线下发命令返回 40901，页面可 `if (err.code === CODE.CONFLICT)` 给出专属提示。
 */
export class ApiError extends Error {
  /**
   * @param {Object} params
   * @param {number} params.code 业务码（网络失败为 {@link NETWORK_ERROR_CODE}）
   * @param {string} params.message 已解析好的可读提示
   * @param {string} [params.traceId] 链路追踪 ID，可拿去后端日志 grep
   * @param {number} [params.httpStatus] HTTP 状态码（网络失败时无）
   * @param {string} [params.url] 请求路径，便于排障
   * @param {string} [params.method] 请求方法，便于排障
   */
  constructor({ code, message, traceId, httpStatus, url, method }) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.message = message
    this.traceId = traceId
    this.httpStatus = httpStatus
    this.url = url
    this.method = method
  }

  /** 是否网络层失败（没到服务端）。 */
  isNetworkError() {
    return this.code === NETWORK_ERROR_CODE
  }

  /** 是否登录态失效（40101）。 */
  isUnauthorized() {
    return this.code === CODE.UNAUTHORIZED
  }
}

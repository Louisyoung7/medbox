/**
 * AI 洞察层纯函数（对应 medbox-spec/05 第 2 章；feat/mp-ai-insight）。
 *
 * <p>LLM 洞察层只消费本项目已有的结构化数据，输出自然语言洞察 / 假设 / 建议，**不落库、
 * 不自动执行**（05 FP-9/FP-10）。本文件只收敛"接口字段 ↔ 前端状态"的纯逻辑，便于单测，
 * 不涉及任何网络与平台 API。
 *
 * <p>错误码走 02 的 1.2 封闭集合（50310 AI 不可用 / 40001 参数 / 40301 权限）；详情见
 * `src/utils/error.js` 的 {@link CODE}。
 */

/** 分析维度枚举（05 第 2.2 章 `type`，`COMPREHENSIVE` 为默认综合维度）。 */
export const AI_TYPE = {
  ADHERENCE: 'ADHERENCE',
  STOCK: 'STOCK',
  ENV_CORRELATION: 'ENV_CORRELATION',
  TREND: 'TREND',
  COMPREHENSIVE: 'COMPREHENSIVE',
}

/**
 * 维度展示信息（标签 + 该维度消费哪些结构化数据，FP-1~4）。
 *
 * <p>`value` 即下发给 `/ai/insight` 的 `type`；`desc` 是给用户的"消费数据"说明，不构成结论。
 */
export const AI_TYPES = [
  { value: 'ADHERENCE', label: '漏服分析', desc: '服药记录 + 计划提醒 + 漏服告警' },
  { value: 'STOCK', label: '库存预测', desc: '库存数量 / 有效期 + 近 N 日消耗' },
  { value: 'ENV_CORRELATION', label: '环境关联', desc: '温湿光遥测 + 对应时段用药' },
  { value: 'TREND', label: '趋势预警', desc: '历史服药 / 告警趋势外推' },
  { value: 'COMPREHENSIVE', label: '综合洞察', desc: '以上全部维度（默认）' },
]

/** 周期洞察节奏（05 第 2.3 章 `DAILY` / `WEEKLY`）。 */
export const SCHEDULE_FREQUENCY = { DAILY: 'DAILY', WEEKLY: 'WEEKLY' }

/** 周期洞察节奏展示。 */
export const SCHEDULE_FREQUENCIES = [
  { value: 'DAILY', label: '每日' },
  { value: 'WEEKLY', label: '每周' },
]

/** 时间范围上限（天）：05 第 2.2 章 `range` 跨度 > 30 天由后端判 40001，前端封顶一致。 */
export const MAX_RANGE_DAYS = 30

const DAY_MS = 24 * 60 * 60 * 1000

/**
 * 本地时间区间 → ISO-8601 UTC 的 `range`（UI 选"近 N 天"预设，封顶 {@link MAX_RANGE_DAYS}）。
 *
 * <p>前端**不自行换算时区**：用本地 `Date` 构造区间端点，再由 `toISOString()` 转 UTC 下发，
 * 与 env/report 页同一口径（05 第 2.2 章 `range.from/to` 为 UTC）。
 *
 * @param {number} [rangeDays=7] 预设天数（1~30）
 * @param {Date} [now] 供单测注入"当前时间"
 * @returns {{from:string, to:string}} ISO-8601 UTC 区间端点
 */
export function buildInsightRange(rangeDays, now = new Date()) {
  const days = Math.min(Math.max(1, Number(rangeDays) || 7), MAX_RANGE_DAYS)
  const to = now
  const from = new Date(to.getTime() - days * DAY_MS)
  return { from: from.toISOString(), to: to.toISOString() }
}

/**
 * 组装洞察请求体（05 第 2.2 章 `{elderId, type, range, deviceId}`）。
 *
 * @param {Object} params
 * @param {string} params.elderId 目标老人（必填）
 * @param {string} [params.type] 维度，缺省 `COMPREHENSIVE`
 * @param {number} [params.rangeDays=7] 时间范围预设天数（封顶 30）
 * @param {string} [params.deviceId] 设备维度（可选）
 * @param {Date} [params.now] 供单测注入
 * @returns {Object} 下发 body
 * @throws {Error} elderId 缺失
 */
export function buildInsightPayload({ elderId, type, rangeDays, deviceId, now } = {}) {
  if (!elderId) {
    throw new Error('elderId 不能为空')
  }
  const payload = {
    elderId,
    type: type || AI_TYPE.COMPREHENSIVE,
    range: buildInsightRange(rangeDays, now),
  }
  if (deviceId) {
    payload.deviceId = deviceId
  }
  return payload
}

/**
 * 流式渲染初始状态（content 增量累积，done 帧收尾 citations/disclaimer）。
 * @returns {{content:string, citations:Array, disclaimer:string, done:boolean}}
 */
export function initialState() {
  return { content: '', citations: [], disclaimer: '', done: false }
}

/**
 * 流式帧 reducer（纯函数，H5/小程序双端共用，可单测）。
 *
 * <p>增量帧 `{type:'delta',content}` 追加文本；结束帧 `{type:'done',citations,disclaimer}`
 * 落定引用与声明并置 `done`；其余帧原样返回。
 *
 * @param {{content:string,citations:Array,disclaimer:string,done:boolean}} state
 * @param {Object} [frame] 可能为 null（流异常时）
 * @returns {Object} 新状态（不可变）
 */
export function applyInsightFrame(state, frame) {
  const s = state || initialState()
  if (!frame) {
    return s
  }
  if (frame.type === 'delta') {
    return { ...s, content: s.content + (frame.content || '') }
  }
  if (frame.type === 'done') {
    return {
      ...s,
      citations: Array.isArray(frame.citations) ? frame.citations : s.citations,
      disclaimer: frame.disclaimer || s.disclaimer,
      done: true,
    }
  }
  return s
}

/**
 * 容错解析洞察响应（05 第 2.2 章 `data` 形状）。
 *
 * <p>后端 `non_null` 会省略空字段，**不能**用 `data.content === undefined` 判异常；缺字段做兜底。
 *
 * @param {Object} [data] 统一响应的 `data`
 * @returns {{elderId:string, type:string, range:?Object, content:string, citations:Array, disclaimer:string}}
 */
export function parseInsight(data) {
  const d = data || {}
  return {
    elderId: typeof d.elderId === 'string' ? d.elderId : '',
    type: typeof d.type === 'string' ? d.type : '',
    range: d.range && typeof d.range === 'object' ? d.range : null,
    content: typeof d.content === 'string' ? d.content : '',
    citations: Array.isArray(d.citations) ? d.citations : [],
    disclaimer: typeof d.disclaimer === 'string' ? d.disclaimer : '',
  }
}

/**
 * 是否"AI 未开通"（05 第 2.4 章：未配置 / Key 无效 → 50310）。
 * @param {*} err 捕获的异常（多为 {@link ApiError}）
 * @returns {boolean}
 */
export function isNoKeyError(err) {
  return !!(err && err.code === 50310)
}

/**
 * 洞察类错误的用户可读文案（按 02 的 1.2 封闭集合细分，不臆造新码）。
 *
 * @param {*} [err] 捕获的异常
 * @returns {string}
 */
export function aiErrorMessage(err) {
  if (!err) {
    return '生成失败，请稍后重试'
  }
  if (err.code === 50310) {
    return 'AI 助手暂未开通，请联系监护人配置'
  }
  if (err.code === 40301) {
    return '无权查看该老人的洞察'
  }
  if (err.code === 40001) {
    return '请求参数有误，请调整分析维度或时间范围'
  }
  return err.message || '生成失败，请稍后重试'
}

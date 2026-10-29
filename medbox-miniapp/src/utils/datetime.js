/**
 * UTC → 本地时间格式化工具（`feat/mp-ui-kit`，对应 medbox-spec/01 第 3 章、08 的 `feat/mp-ui-kit`）。
 *
 * <p>后端时间一律为 UTC（ISO 8601 带 `Z`，见文档 02 / 04 / 06）。本模块把 UTC 字符串解析成
 * `Date`，再用**设备本地时区**输出，保证"时间显示与后端 UTC 一致"。
 *
 * <p>纯函数、可单测（见 `__tests__/datetime.test.js`，固定 `TZ=Asia/Shanghai`）。
 */

const PAD = (n) => String(n).padStart(2, '0')

/**
 * 把任意时间输入解析成 `Date`；无法解析返回 `null`。
 *
 * <p>后端时间带 `Z` 时按 UTC 解释；缺时区偏移的日期时间串按 UTC 兜底（与后端契约一致），
 * 避免被当成本地时间导致偏差 8 小时。
 *
 * @param {string|number|Date} input
 * @returns {Date|null}
 */
export function parseUtc(input) {
  if (input == null) return null
  if (input instanceof Date) return isNaN(input.getTime()) ? null : input
  let str = String(input).trim()
  if (!str) return null
  // 纯数字（时间戳）直接转
  if (/^\d+$/.test(str)) {
    const d = new Date(Number(str))
    return isNaN(d.getTime()) ? null : d
  }
  // 缺时区偏移的「日期+时间」串补 Z，按 UTC 处理
  if (
    !/[zZ]$/.test(str) &&
    !/[+\-]\d{2}:?\d{2}$/.test(str) &&
    /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2})?/.test(str)
  ) {
    str = str.replace(' ', 'T') + 'Z'
  }
  const d = new Date(str)
  return isNaN(d.getTime()) ? null : d
}

/**
 * 格式化成绝对时间（本地时区）。
 *
 * @param {*} input 见 {@link parseUtc}
 * @param {string} [pattern='YYYY-MM-DD HH:mm'] 支持 YYYY / MM / DD / HH / mm / ss
 * @returns {string} 解析失败返回 '-'
 */
export function formatDateTime(input, pattern = 'YYYY-MM-DD HH:mm') {
  const d = parseUtc(input)
  if (!d) return '-'
  const map = {
    YYYY: String(d.getFullYear()),
    MM: PAD(d.getMonth() + 1),
    DD: PAD(d.getDate()),
    HH: PAD(d.getHours()),
    mm: PAD(d.getMinutes()),
    ss: PAD(d.getSeconds()),
  }
  return pattern.replace(/YYYY|MM|DD|HH|mm|ss/g, (m) => map[m])
}

/** 只到日的绝对时间（`YYYY-MM-DD`）。 */
export function formatDate(input) {
  return formatDateTime(input, 'YYYY-MM-DD')
}

/**
 * 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / 超过一周回退绝对日期。
 *
 * @param {*} input 见 {@link parseUtc}
 * @param {Date|number} [now=Date.now()] 基准时间（测试可注入固定值）
 * @returns {string} 未来时间直接回退绝对时间；解析失败返回 '-'
 */
export function formatRelative(input, now = new Date()) {
  const d = parseUtc(input)
  if (!d) return '-'
  const base = now instanceof Date ? now : new Date(now)
  const diffMs = base.getTime() - d.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 0) return formatDateTime(input) // 未来：直接绝对
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin}分钟前`
  const diffHr = Math.floor(diffMin / 60)
  if (diffHr < 24) return `${diffHr}小时前`
  const diffDay = Math.floor(diffHr / 24)
  if (diffDay === 1) return '昨天'
  if (diffDay < 7) return `${diffDay}天前`
  return formatDate(input)
}

/**
 * 距离目标日期还有多少天（按本地日期 0 点计算，去掉时分秒）。负数 = 已过期。
 *
 * @param {*} input 到期日（药品有效期通常只到日）
 * @param {Date|number} [from=new Date()] 基准时间
 * @returns {number|null} 解析失败返回 null
 */
export function daysUntil(input, from = new Date()) {
  const d = parseUtc(input)
  if (!d) return null
  const base = from instanceof Date ? from : new Date(from)
  const startOfDay = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  return Math.round((startOfDay(d) - startOfDay(base)) / 86400000)
}



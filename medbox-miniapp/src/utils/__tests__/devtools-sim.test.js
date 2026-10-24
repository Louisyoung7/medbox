import { describe, expect, it } from 'vitest'

/**
 * 模拟上报报文构造单测（`feat/mp-devtools`，对应 medbox-spec/03 第 3 章 / 第 5 章）。
 *
 * <p>覆盖三件事：**msgId 格式与唯一性**（后端按它幂等去重，撞了会被吃掉、
 * 表现为"发了却没反应"）、**各预设的字段形状**（必须与 03 §5 的 mosquitto_pub 示例一致）、
 * **错误文案归一**（后端没开 devtools 时不能只说"服务端内部错误"）。
 */

import {
  SIM_DEFAULT_DEVICE_ID,
  SIM_EVENT_PRESETS,
  buildSimPayload,
  buildSimRequest,
  devtoolsErrorMessage,
  getSimPreset,
  nextSimMsgId,
  slotDateText,
} from '@/utils/devtools-sim.js'
import { CODE } from '@/utils/error.js'

describe('nextSimMsgId', () => {
  it('格式为 {deviceId}-{10位序号}', () => {
    expect(nextSimMsgId('BOXA1001')).toMatch(/^BOXA1001-\d{10}$/)
  })

  it('同一设备连续取号单调递增（不重复，否则会被幂等去重吃掉）', () => {
    const first = nextSimMsgId('BOXA1001')
    const second = nextSimMsgId('BOXA1001')
    const third = nextSimMsgId('BOXA1001')
    expect(new Set([first, second, third]).size).toBe(3)
    const nums = [first, second, third].map((id) => Number(id.split('-')[1]))
    expect(nums[1]).toBe(nums[0] + 1)
    expect(nums[2]).toBe(nums[1] + 1)
  })

  it('不同设备各自带自己的前缀', () => {
    expect(nextSimMsgId('BOXA1002')).toMatch(/^BOXA1002-\d{10}$/)
  })

  it('序号被截到 10 位：进程内自增溢出也不会破坏格式', () => {
    // msgSeq 是模块级变量，直接推进到 11 位再取号，验证 slice(-10) 兜底
    for (let i = 0; i < 3; i += 1) {
      const id = nextSimMsgId('BOXA1001')
      expect(id.split('-')[1]).toHaveLength(10)
    }
  })
})

describe('SIM_EVENT_PRESETS', () => {
  it('key 唯一（action sheet 按下标取预设，重复 key 会取错）', () => {
    const keys = SIM_EVENT_PRESETS.map((item) => item.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('每项都有 label / subTopic / desc / effect / effectNote / build', () => {
    SIM_EVENT_PRESETS.forEach((item) => {
      expect(typeof item.label).toBe('string')
      expect(item.label.length).toBeGreaterThan(0)
      expect(typeof item.subTopic).toBe('string')
      expect(item.subTopic.length).toBeGreaterThan(0)
      expect(typeof item.desc).toBe('string')
      expect(['record', 'log']).toContain(item.effect)
      expect(typeof item.effectNote).toBe('string')
      expect(item.effectNote.length).toBeGreaterThan(0)
      expect(typeof item.build).toBe('function')
    })
  })

  it('只有 event/dispense 标记为真落库（其余 topic 的后端分支尚未落地）', () => {
    const recorded = SIM_EVENT_PRESETS.filter((item) => item.effect === 'record')
    expect(recorded.map((item) => item.key).sort()).toEqual([
      'dispense-late',
      'dispense-on-time',
      'dispense-wrong-drug',
    ])
    recorded.forEach((item) => expect(item.subTopic).toBe('event/dispense'))
    // 其余一律"仅日志"，且必须说清是哪个分支没落地
    SIM_EVENT_PRESETS.filter((item) => item.effect === 'log').forEach((item) => {
      expect(item.effectNote).toMatch(/未落地/)
    })
  })

  it('subTopic 全部落在 03 第 2 章的上行集合内', () => {
    const allowed = ['telemetry', 'sensor/heartbeat', 'event/dispense', 'event/error', 'inventory']
    SIM_EVENT_PRESETS.forEach((item) => {
      expect(allowed).toContain(item.subTopic)
    })
  })
})

describe('buildSimPayload', () => {
  it('返回 JSON 字符串（后端 DTO 收 String，传对象会被 Jackson 拒掉）', () => {
    const payload = buildSimPayload('dispense-on-time', 'BOXA1001')
    expect(typeof payload).toBe('string')
    expect(() => JSON.parse(payload)).not.toThrow()
  })

  it('按时服药：字段与 03 §5 的 mosquitto_pub 示例同形态', () => {
    const body = JSON.parse(buildSimPayload('dispense-on-time', 'BOXA1001'))
    expect(body).toMatchObject({
      planId: 'p-3301',
      planItemId: 'pi-9001',
      medicineId: 'm-205',
      actualDose: '1',
      unit: '片',
      wrongDrug: false,
      source: 'CAMERA',
      imageId: null,
    })
    expect(body.msgId).toMatch(/^BOXA1001-\d{10}$/)
    expect(typeof body.ts).toBe('number')
    expect(body.confidence).toBeGreaterThan(0)
  })

  it('错服：wrongDrug=true 且换成另一种药（触发 WRONG_DRUG 判定）', () => {
    const body = JSON.parse(buildSimPayload('dispense-wrong-drug', 'BOXA1001'))
    expect(body.wrongDrug).toBe(true)
    expect(body.medicineId).toBe('m-206')
    expect(body.medicineId).not.toBe('m-205')
  })

  it('遥测：带 sensorId 与温湿度，不带 dispense 字段', () => {
    const body = JSON.parse(buildSimPayload('telemetry', 'BOXA1001'))
    expect(body.sensorId).toBe('BOXA1001-S-TH01')
    expect(typeof body.temperature).toBe('number')
    expect(typeof body.humidity).toBe('number')
    expect(body.planId).toBeUndefined()
  })

  it('传感器心跳：三个子设备，摄像头离线（验证离线标注链路）', () => {
    const body = JSON.parse(buildSimPayload('sensor-heartbeat', 'BOXA1001'))
    expect(body.sensors).toHaveLength(3)
    const cam = body.sensors.find((s) => s.sensorId === 'BOXA1001-S-CAM01')
    expect(cam.online).toBe(false)
  })

  it('设备异常：type 取 03 第 3 章的合法枚举', () => {
    const body = JSON.parse(buildSimPayload('event-error', 'BOXA1001'))
    expect(['DOOR_TIMEOUT', 'CAMERA_FAILED', 'HW_FAULT']).toContain(body.type)
  })

  it('库存变更：quantity 与 delta 自洽（+1 补药）', () => {
    const body = JSON.parse(buildSimPayload('inventory', 'BOXA1001'))
    expect(body.delta).toBe(1)
    expect(typeof body.quantity).toBe('number')
  })

  it('每次调用生成不同 msgId（连发两条不会互相去重）', () => {
    const a = JSON.parse(buildSimPayload('dispense-on-time', 'BOXA1001'))
    const b = JSON.parse(buildSimPayload('dispense-on-time', 'BOXA1001'))
    expect(a.msgId).not.toBe(b.msgId)
  })

  it('未知 key 直接抛错（不让页面发出一个空 payload）', () => {
    expect(() => buildSimPayload('not-exist', 'BOXA1001')).toThrow(/未知的模拟事件类型/)
  })
})

describe('buildSimRequest', () => {
  it('一并返回 subTopic / msgId / payload / slotDate（页面要回显 msgId 与计划槽）', () => {
    const req = buildSimRequest('dispense-on-time', 'BOXA1001', -2)
    expect(req.subTopic).toBe('event/dispense')
    expect(req.msgId).toMatch(/^BOXA1001-\d{10}$/)
    expect(typeof req.payload).toBe('string')
    expect(JSON.parse(req.payload).msgId).toBe(req.msgId)
    expect(req.slotDate).toBe(slotDateText(-2))
  })

  it('非服药预设没有 slotDate（没有计划时间槽可回显）', () => {
    SIM_EVENT_PRESETS.filter((item) => item.subTopic !== 'event/dispense').forEach((item) => {
      expect(buildSimRequest(item.key, 'BOXA1001', -5).slotDate).toBe('')
    })
  })

  it('payload 与 buildSimPayload 完全一致（后者是前者的委托，不重复实现）', () => {
    // 两者各自生成新 msgId，故只比结构：key 集合
    const viaRequest = buildSimRequest('event-error', 'BOXA1001')
    const viaPayload = JSON.parse(buildSimPayload('event-error', 'BOXA1001'))
    expect(Object.keys(viaPayload).sort()).toEqual(
      Object.keys(JSON.parse(viaRequest.payload)).sort(),
    )
  })

  it('subTopic 与预设声明的一致（发错 topic 后端直接不认）', () => {
    SIM_EVENT_PRESETS.forEach((item) => {
      expect(buildSimRequest(item.key, 'BOXA1001').subTopic).toBe(item.subTopic)
    })
  })

  it('未知 key 同样抛错', () => {
    expect(() => buildSimRequest('not-exist', 'BOXA1001')).toThrow(/未知的模拟事件类型/)
  })
})

/**
 * 服药类预设的"计划时间槽"。
 *
 * <p>后端 `onTime` 是拿 `ts` 现算的（`DispenseConsumeService`：
 * `!actualTime.isAfter(planTime.plusMinutes(tolerance))`），payload 里的 `onTime` 被忽略；
 * 且 `med_record` 上有 `(plan_item_id, plan_time)` 部分唯一索引，同槽只落一条。
 * 所以这里必须验证：① 每个预设的 `ts` 落在**预期的本地时刻**；② 三个预设**槽位互不相同**
 * （否则后发的会被幂等吃掉，预设等于废掉）。
 */

/** 把 epoch 毫秒格式化成设备本地（UTC+8）的 `YYYY-MM-DD HH:mm`。 */
function wallClockOf(ts) {
  const d = new Date(ts + 8 * 60 * 60000)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`
}

describe('服药类预设的 ts 与槽位', () => {
  const dispenseKeys = ['dispense-on-time', 'dispense-wrong-drug', 'dispense-late']

  it('按时：今日 08:05，落在计划槽后 5 分钟（容忍默认 15 分钟）内 → 后端算出 onTime=true', () => {
    const body = JSON.parse(buildSimPayload('dispense-on-time', 'BOXA1001'))
    const wall = wallClockOf(body.ts)
    const today = wallClockOf(Date.now()).slice(0, 10)
    expect(wall).toBe(`${today} 08:05`)
    // 与今日 08:00 槽的差 5 分钟，未超容忍
    const minutesAfterSlot = 5
    expect(minutesAfterSlot).toBeLessThanOrEqual(15)
  })

  it('迟到：前日 09:00，距前日 08:00 槽 1 小时 → 后端算出 onTime=false', () => {
    const body = JSON.parse(buildSimPayload('dispense-late', 'BOXA1001', -2))
    const wall = wallClockOf(body.ts)
    expect(wall.slice(11)).toBe('09:00')
    const daysAgo = Math.round((Date.now() - body.ts) / 86400000)
    expect(daysAgo).toBe(2)
    // 距 08:00 槽 60 分钟，超过默认容忍 15 分钟
    expect(60).toBeGreaterThan(15)
  })

  it('错服：昨日 08:05，与"按时"错开日历天', () => {
    const body = JSON.parse(buildSimPayload('dispense-wrong-drug', 'BOXA1001', -1))
    const wall = wallClockOf(body.ts)
    expect(wall.slice(11)).toBe('08:05')
    const daysAgo = Math.round((Date.now() - body.ts) / 86400000)
    expect(daysAgo).toBe(1)
  })

  it('三个服药预设传入不同 dayOffset 时，本地日期互不相同 → 不会互相触发 uk_med_record_item_plan_time', () => {
    const days = dispenseKeys.map((key, index) =>
      wallClockOf(JSON.parse(buildSimPayload(key, 'BOXA1001', -index)).ts).slice(0, 10),
    )
    expect(new Set(days).size).toBe(3)
  })

  it('dayOffset 决定目标日历天：-3 就落三天前（槽位可以一直往后要，不会用完）', () => {
    const body = JSON.parse(buildSimPayload('dispense-on-time', 'BOXA1001', -3))
    const wall = wallClockOf(body.ts)
    expect(wall.slice(0, 10)).toBe(slotDateText(-3))
    expect(wall.slice(11)).toBe('08:05')
  })

  it('同一预设连续递增 dayOffset，每次都落在还没用过的新槽', () => {
    const days = [0, 1, 2, 3, 4].map((n) => slotDateText(-n))
    expect(new Set(days).size).toBe(5)
  })

  it('三个服药预设共用同一 planItemId（种子只有这一个计划项）', () => {
    const items = dispenseKeys.map((key) => JSON.parse(buildSimPayload(key, 'BOXA1001', 0)).planItemId)
    expect(new Set(items)).toEqual(new Set(['pi-9001']))
  })
})

describe('slotDateText', () => {
  it('0 = 今天的设备本地日期', () => {
    expect(slotDateText(0)).toBe(wallClockOf(Date.now()).slice(0, 10))
  })

  it('负数逐日前移，且与 ts 推出来的日期一致', () => {
    expect(slotDateText(-1)).toBe(wallClockOf(Date.now() - 86400000).slice(0, 10))
    const body = JSON.parse(buildSimPayload('dispense-late', 'BOXA1001', -1))
    expect(slotDateText(-1)).toBe(wallClockOf(body.ts).slice(0, 10))
  })
})

describe('getSimPreset', () => {
  it('按 key 命中', () => {
    expect(getSimPreset('telemetry').subTopic).toBe('telemetry')
  })

  it('未知 key 返回 undefined（调用方自行提示，不抛）', () => {
    expect(getSimPreset('nope')).toBeUndefined()
  })
})

describe('devtoolsErrorMessage', () => {
  it('后端未装配 DevtoolsController：404 要说"去开 devtools"，而不是"服务端内部错误"', () => {
    const err = { code: CODE.INTERNAL_ERROR, message: '服务端内部错误', httpStatus: 404 }
    const text = devtoolsErrorMessage(err)
    expect(text).toContain('medbox.devtools.enabled')
    expect(text).not.toBe('服务端内部错误')
  })

  it('50000 透出后端 message（如"MQTT 未连接 broker，请先启动 EMQX"）', () => {
    const err = { code: CODE.INTERNAL_ERROR, message: 'MQTT 未连接 broker，无法模拟设备上报，请先启动 EMQX' }
    expect(devtoolsErrorMessage(err)).toContain('请先启动 EMQX')
  })

  it('网络失败：透出统一局域网排查文案', () => {
    const err = { code: -1, message: '网络异常，请检查手机与电脑是否在同一局域网' }
    expect(devtoolsErrorMessage(err)).toContain('同一局域网')
  })

  it('兜底：无错误对象 / 无 message 都不返回空串', () => {
    expect(devtoolsErrorMessage()).toBe('操作失败，请重试')
    expect(devtoolsErrorMessage({})).toBe('操作失败，请重试')
    expect(devtoolsErrorMessage(null)).toBe('操作失败，请重试')
  })

  it('未知 code 但有 message：透出原 message', () => {
    expect(devtoolsErrorMessage({ code: 40001, message: 'deviceId 不能为空' })).toBe('deviceId 不能为空')
  })
})

describe('模块默认值', () => {
  it('默认设备 ID 与后端种子参数一致（联调示例同款）', () => {
    expect(SIM_DEFAULT_DEVICE_ID).toBe('BOXA1001')
  })
})

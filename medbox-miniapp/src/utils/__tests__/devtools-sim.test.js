import { describe, it, expect } from 'vitest'
import {
  SIM_DEFAULT_DEVICE_ID,
  SIM_EVENT_PRESETS,
  getSimPreset,
  nextSimMsgId,
  slotDateText,
  buildSimRequest,
  buildSimPayload,
  devtoolsErrorMessage,
} from '@/utils/devtools-sim.js'

describe('SIM_EVENT_PRESETS（固定药格模型：服药报文带 cellNo）', () => {
  it('预设 5 项，MQTT 预设全部仅记日志', () => {
    expect(SIM_EVENT_PRESETS).toHaveLength(5)
    expect(SIM_EVENT_PRESETS.every((p) => p.effect === 'log')).toBe(true)
  })
  it('服药预设只带 cellNo + takenResult，不含药品 / 剂量 / 计划项', () => {
    ;['dispense-on-time', 'dispense-late'].forEach((key) => {
      const body = getSimPreset(key).build(SIM_DEFAULT_DEVICE_ID, 0)
      expect(body.cellNo).toBe(1)
      expect(body.planId).toBe('p-3301')
      expect(body.takenResult).toBe('TAKEN')
      expect(body.planItemId).toBeUndefined()
      expect(body.medicineId).toBeUndefined()
      expect(body.actualDose).toBeUndefined()
      expect(body.unit).toBeUndefined()
    })
  })
  it('库存变更预设已随台账下线移除', () => {
    expect(getSimPreset('inventory')).toBeUndefined()
    expect(SIM_EVENT_PRESETS.map((p) => p.subTopic)).not.toContain('inventory')
  })
})

describe('msgId 与日期工具', () => {
  it('msgId 形如 {deviceId}-{10位序号}', () => {
    expect(nextSimMsgId(SIM_DEFAULT_DEVICE_ID)).toMatch(/^BOXA-DEBUG-01-\d{10}$/)
  })
  it('slotDateText 返回 YYYY-MM-DD', () => {
    expect(slotDateText(0)).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})

describe('buildSimRequest / buildSimPayload', () => {
  it('服药类返回 subTopic / msgId / payload 字符串 / slotDate', () => {
    const req = buildSimRequest('dispense-on-time', SIM_DEFAULT_DEVICE_ID, -1)
    expect(req.subTopic).toBe('event/dispense')
    expect(req.msgId).toMatch(/^BOXA-DEBUG-01-\d{10}$/)
    expect(req.slotDate).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    const body = JSON.parse(req.payload)
    expect(body.cellNo).toBe(1)
    expect(body.planItemId).toBeUndefined()
  })
  it('非服药类 slotDate 为空串；payload 统一序列化为 JSON 字符串', () => {
    const req = buildSimRequest('telemetry', SIM_DEFAULT_DEVICE_ID)
    expect(req.slotDate).toBe('')
    // msgId 每次调用自增，故只校验形态而不比较两次调用的整串
    const payload = JSON.parse(buildSimPayload('telemetry', SIM_DEFAULT_DEVICE_ID))
    expect(payload.sensorId).toBe('BOXA-DEBUG-01-S-TH01')
    expect(payload.msgId).toMatch(/^BOXA-DEBUG-01-\d{10}$/)
    expect(payload.cellNo).toBeUndefined()
  })
  it('未知 preset → 抛错', () => {
    expect(() => buildSimRequest('unknown', SIM_DEFAULT_DEVICE_ID)).toThrow()
  })
})

describe('devtoolsErrorMessage', () => {
  it('404 → 提示开启调试端点；50000 透出后端 message', () => {
    expect(devtoolsErrorMessage({ httpStatus: 404 })).toContain('medbox.devtools.enabled')
    expect(devtoolsErrorMessage({ code: 50000, message: 'EMQX 未连接' })).toBe('EMQX 未连接')
    expect(devtoolsErrorMessage(null)).toBe('操作失败，请重试')
  })
})

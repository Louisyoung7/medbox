import { describe, it, expect } from 'vitest'
import {
  buildCaptureFileUrl,
  parseCapture,
  buildConfirmPayload,
  parseCapturesPage,
  toCaptureView,
  isExpired,
  REASON_LABEL,
  TAKEN_RESULT_LABEL,
} from '@/utils/capture.js'

describe('buildCaptureFileUrl', () => {
  it('把 captureId 与 fileToken 拼到 /file?token=', () => {
    expect(buildCaptureFileUrl('cap 1', 't/1', 'http://host/api'))
      .toBe('http://host/api/captures/cap%201/file?token=t%2F1')
  })
})

describe('parseCapture', () => {
  it('缺失字段兜底；cellNo 越界归 null', () => {
    expect(parseCapture(null)).toMatchObject({ captureId: '', fileToken: '', cellNo: null })
    expect(parseCapture({ cellNo: 2, captureId: 'cap-1' }).cellNo).toBe(2)
    expect(parseCapture({ cellNo: 9 }).cellNo).toBeNull()
  })
})

describe('buildConfirmPayload（02e 单接口双语义）', () => {
  it('纠错：只传非空的 takenResult / actualTime / onTime', () => {
    expect(buildConfirmPayload({ takenResult: 'TAKEN', note: '忽略' }))
      .toEqual({ takenResult: 'TAKEN' })
    expect(buildConfirmPayload({ onTime: false, actualTime: '2026-11-12T00:00:00Z' }))
      .toEqual({ actualTime: '2026-11-12T00:00:00Z', onTime: false })
  })
  it('补录：必带 elderId / planId / cellNo', () => {
    expect(buildConfirmPayload({
      elderId: 'u-1001', planId: 'p-3301', cellNo: 3,
      takenResult: 'UNTAKEN', note: '忽略',
    })).toEqual({ elderId: 'u-1001', planId: 'p-3301', cellNo: 3, takenResult: 'UNTAKEN' })
  })
  it('非法格号 / 非法结论不入参', () => {
    expect(buildConfirmPayload({ cellNo: 4, takenResult: 'UNCERTAIN' })).toEqual({})
  })
})

describe('parseCapturesPage', () => {
  it('兼容四种外壳', () => {
    expect(parseCapturesPage([{ captureId: 'c1' }]).total).toBe(1)
    expect(parseCapturesPage({ list: [{ captureId: 'c1' }], total: 7, page: 2, size: 3 }))
      .toMatchObject({ total: 7, page: 2, size: 3 })
    expect(parseCapturesPage({ records: [{}] }).list).toHaveLength(1)
    expect(parseCapturesPage({ items: [{}] }).list).toHaveLength(1)
  })
})

describe('toCaptureView', () => {
  it('只带格号，不带药品 ID', () => {
    const view = toCaptureView({ captureId: 'cap-1', cellNo: 2, takenResult: 'UNTAKEN', reason: 'NORMAL' })
    expect(view.cellNo).toBe(2)
    expect(view.cellLabel).toBe('2 号格（午间）')
    expect(view.takenResultLabel).toBe('未服')
    expect(view.medicineId).toBeUndefined()
  })
  it('无格号 / 无字段兜底', () => {
    const view = toCaptureView(null)
    expect(view.cellNo).toBeNull()
    expect(view.expired).toBe(false)
    expect(view.capturedAtText).toBeDefined()
  })
})

describe('常量与过期判定', () => {
  it('reason / takenResult 文案', () => {
    expect(REASON_LABEL.MANUAL).toBe('主动留证')
    expect(TAKEN_RESULT_LABEL.TAKEN).toBe('已服')
  })
  it('isExpired：过期时刻为 true，缺失为 false', () => {
    const now = new Date('2026-11-12T00:00:00Z')
    expect(isExpired('2026-11-11T00:00:00Z', now)).toBe(true)
    expect(isExpired('2026-11-13T00:00:00Z', now)).toBe(false)
    expect(isExpired('', now)).toBe(false)
  })
})

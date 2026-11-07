import { describe, it, expect } from 'vitest'
import {
  buildCaptureFileUrl,
  parseCapture,
  buildConfirmPayload,
  parseCapturesPage,
  REASON_LABEL,
  TAKEN_RESULT_LABEL,
  isExpired,
  toCaptureView,
} from '@/utils/capture.js'

describe('buildCaptureFileUrl', () => {
  it('拼接 fileToken 到 query', () => {
    expect(buildCaptureFileUrl('cap-1', 'tk-9', 'http://h:8080/medbox/api/v1'))
      .toBe('http://h:8080/medbox/api/v1/captures/cap-1/file?token=tk-9')
  })
  it('默认 baseUrl 空串返回相对路径', () => {
    expect(buildCaptureFileUrl('cap-1', 'tk-9')).toBe('/captures/cap-1/file?token=tk-9')
  })
  it('空值兜底', () => {
    expect(buildCaptureFileUrl('', '')).toBe('/captures//file?token=')
  })
})

describe('parseCapture', () => {
  it('正常映射 takenResult / fileToken / expireAt / mime', () => {
    const src = { captureId: 'cap-1', fileToken: 'tk', takenResult: 'TAKEN', expireAt: '2026-10-24T00:00:00Z', mime: 'image/jpeg' }
    expect(parseCapture(src)).toMatchObject({
      captureId: 'cap-1', fileToken: 'tk', takenResult: 'TAKEN', expireAt: '2026-10-24T00:00:00Z', mime: 'image/jpeg',
    })
  })
  it('缺失字段兜底空串（不抛错）', () => {
    expect(parseCapture(null)).toEqual({ captureId: '', fileToken: '', takenResult: '', expireAt: '', mime: '' })
    expect(parseCapture({})).toEqual({ captureId: '', fileToken: '', takenResult: '', expireAt: '', mime: '' })
  })
})

describe('buildConfirmPayload', () => {
  it('二值 takenResult 入参，note 去空白', () => {
    expect(buildConfirmPayload({ takenResult: 'TAKEN', note: ' 已核对原图 ' })).toEqual({ takenResult: 'TAKEN', note: '已核对原图' })
    expect(buildConfirmPayload({ takenResult: 'UNTAKEN', note: '未服' })).toEqual({ takenResult: 'UNTAKEN', note: '未服' })
  })
  it('空 takenResult 不入参，空 note 不传', () => {
    expect(buildConfirmPayload({ note: '   ' })).toEqual({})
    expect(buildConfirmPayload({})).toEqual({})
    expect(buildConfirmPayload({ takenResult: 'OTHER' })).toEqual({})
  })
})

describe('parseCapturesPage', () => {
  it('裸数组兜底 list/total/page/size', () => {
    const arr = [{ captureId: 'a' }, { captureId: 'b' }]
    expect(parseCapturesPage(arr)).toEqual({ list: arr, total: 2, page: 1, size: 2 })
  })
  it('兼容 list/records/items 与 total', () => {
    expect(parseCapturesPage({ list: [], total: 0 })).toEqual({ list: [], total: 0, page: 1, size: 0 })
    expect(parseCapturesPage({ records: [{ captureId: 'a' }], total: 5, page: 2, size: 1 }))
      .toEqual({ list: [{ captureId: 'a' }], total: 5, page: 2, size: 1 })
    expect(parseCapturesPage({ items: [{ captureId: 'a' }], total: 1 })).toEqual({ list: [{ captureId: 'a' }], total: 1, page: 1, size: 1 })
  })
  it('null/undefined 兜底空列表', () => {
    expect(parseCapturesPage(null)).toEqual({ list: [], total: 0, page: 1, size: 0 })
    expect(parseCapturesPage(undefined)).toEqual({ list: [], total: 0, page: 1, size: 0 })
  })
})

describe('REASON_LABEL', () => {
  it('NORMAL/MANUAL 文案', () => {
    expect(REASON_LABEL).toEqual({ NORMAL: '正常服药', MANUAL: '主动留证' })
  })
})

describe('TAKEN_RESULT_LABEL', () => {
  it('TAKEN/UNTAKEN 文案', () => {
    expect(TAKEN_RESULT_LABEL).toEqual({ TAKEN: '已服', UNTAKEN: '未服' })
  })
})

describe('isExpired', () => {
  it('expireAt 早于 now → true', () => {
    expect(isExpired('2026-10-24T00:00:00Z', new Date('2026-10-25T00:00:00Z'))).toBe(true)
  })
  it('expireAt 晚于 now → false', () => {
    expect(isExpired('2026-10-26T00:00:00Z', new Date('2026-10-25T00:00:00Z'))).toBe(false)
  })
  it('缺省 expireAt → false（交给后端）', () => {
    expect(isExpired('', new Date('2026-10-25T00:00:00Z'))).toBe(false)
    expect(isExpired(undefined, new Date('2026-10-25T00:00:00Z'))).toBe(false)
  })
})

describe('toCaptureView', () => {
  it('归一化字段 + 文案 + 过期标记', () => {
    const v = toCaptureView({
      captureId: 'cap-1',
      deviceId: 'dev-1',
      reason: 'NORMAL',
      takenResult: 'TAKEN',
      mime: 'image/jpeg',
      sizeBytes: 1024,
      capturedAt: '2026-10-23T00:05:00Z',
      expireAt: '2026-10-24T00:00:00Z',
      fileToken: 'tk',
    })
    expect(v.captureId).toBe('cap-1')
    expect(v.deviceId).toBe('dev-1')
    expect(v.reasonLabel).toBe('正常服药')
    expect(v.takenResult).toBe('TAKEN')
    expect(v.takenResultLabel).toBe('已服')
    expect(v.mime).toBe('image/jpeg')
    expect(v.sizeBytes).toBe(1024)
    expect(v.capturedAtText).toBeTruthy()
    expect(v.expireAtText).toBeTruthy()
    expect(v.expired).toBe(true)
  })
  it('缺省字段兜底（不抛错）', () => {
    const v = toCaptureView(null)
    expect(v.captureId).toBe('')
    expect(v.reasonLabel).toBe('未知')
    expect(v.takenResultLabel).toBe('')
    expect(v.expired).toBe(false)
  })
})

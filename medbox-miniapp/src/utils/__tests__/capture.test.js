/**
 * 抓拍 / 待确认纯函数测试（`feat/mp-capture-event`，`src/utils/capture.js`）。
 *
 * <p>覆盖：待确认判定、fileToken 图片 URL 拼接、抓拍元数据容错解析、确认请求体组装。
 */

import { describe, expect, it } from 'vitest'
import {
  buildCaptureFileUrl,
  buildConfirmPayload,
  isUncertain,
  parseCapture,
  parseCapturesPage,
  toCaptureView,
  isExpired,
  REASON_LABEL,
  FACE_RESULT_LABEL,
} from '@/utils/capture.js'

describe('isUncertain', () => {
  it('pendingConfirm=true → 待确认', () => {
    expect(isUncertain({ pendingConfirm: true })).toBe(true)
  })
  it('faceResult=UNCERTAIN → 待确认', () => {
    expect(isUncertain({ faceResult: 'UNCERTAIN' })).toBe(true)
  })
  it('正常已服 / 已确认 → 非待确认', () => {
    expect(isUncertain({ faceResult: 'TAKEN', pendingConfirm: false })).toBe(false)
    expect(isUncertain({ faceResult: 'ABSENT' })).toBe(false)
  })
  it('缺失字段兜底为 false', () => {
    expect(isUncertain({})).toBe(false)
    expect(isUncertain(null)).toBe(false)
  })
})

describe('buildCaptureFileUrl', () => {
  it('拼接 BASE_URL + 编码后的 captureId + token', () => {
    expect(buildCaptureFileUrl('cap-1', 'tok x', 'http://h:8080/medbox/api/v1')).toBe(
      'http://h:8080/medbox/api/v1/captures/cap-1/file?token=tok%20x',
    )
  })
  it('默认空 baseUrl → 相对路径（便于单测）', () => {
    expect(buildCaptureFileUrl('cap-1', 'tok')).toBe('/captures/cap-1/file?token=tok')
  })
})

describe('parseCapture', () => {
  it('抽取字段，数字 confidence 原样保留', () => {
    expect(
      parseCapture({
        captureId: 'cap-1',
        fileToken: 't',
        faceResult: 'UNCERTAIN',
        confidence: 0.52,
        expireAt: 'x',
        mime: 'image/png',
      }),
    ).toEqual({
      captureId: 'cap-1',
      fileToken: 't',
      faceResult: 'UNCERTAIN',
      confidence: 0.52,
      expireAt: 'x',
      mime: 'image/png',
    })
  })
  it('confidence 容错：字符串转数字 / 非法与空置 null', () => {
    expect(parseCapture({ confidence: '0.6' }).confidence).toBe(0.6)
    expect(parseCapture({}).confidence).toBe(null)
    expect(parseCapture({ confidence: 'abc' }).confidence).toBe(null)
  })
  it('缺失兜底为空串 / null', () => {
    const p = parseCapture(null)
    expect(p.captureId).toBe('')
    expect(p.fileToken).toBe('')
    expect(p.faceResult).toBe('')
    expect(p.confidence).toBe(null)
  })
})

describe('buildConfirmPayload', () => {
  it('UNCERTAIN 确认：合法 faceResult + 非空 note 入参', () => {
    expect(buildConfirmPayload({ faceResult: 'TAKEN', note: '已核对' })).toEqual({
      faceResult: 'TAKEN',
      note: '已核对',
    })
    // note 仅空白 → 不入参
    expect(buildConfirmPayload({ faceResult: 'ABSENT', note: '   ' })).toEqual({ faceResult: 'ABSENT' })
  })
  it('非法 faceResult 不入参；note 去空白', () => {
    expect(buildConfirmPayload({ faceResult: 'WEIRD', note: ' x ' })).toEqual({ note: 'x' })
    expect(buildConfirmPayload({})).toEqual({})
  })
})

describe('parseCapturesPage', () => {
  it('裸数组 → list + total=length + 默认分页', () => {
    const r = parseCapturesPage([{ captureId: 'a' }, { captureId: 'b' }])
    expect(r).toEqual({ list: [{ captureId: 'a' }, { captureId: 'b' }], total: 2, page: 1, size: 2 })
  })
  it('标准 {list,total,page,size}', () => {
    const r = parseCapturesPage({ list: [{ captureId: 'a' }], total: 9, page: 2, size: 1 })
    expect(r).toEqual({ list: [{ captureId: 'a' }], total: 9, page: 2, size: 1 })
  })
  it('兼容 {records} / {items} 外壳', () => {
    expect(parseCapturesPage({ records: [{ captureId: 'x' }] }).list.length).toBe(1)
    expect(parseCapturesPage({ items: [{ captureId: 'y' }] }).list.length).toBe(1)
  })
  it('total 缺失 → 用 list.length 兜底', () => {
    expect(parseCapturesPage({ list: [{ captureId: 'a' }] }).total).toBe(1)
  })
})

describe('toCaptureView', () => {
  it('字段兜底 + reason/faceResult 文案', () => {
    const v = toCaptureView({ captureId: 'c1', reason: 'MANUAL', faceResult: 'TAKEN', expireAt: '2026-01-01T00:00:00Z' })
    expect(v.captureId).toBe('c1')
    expect(v.reasonLabel).toBe('主动留证')
    expect(v.faceResultLabel).toBe('已服')
    expect(v.uncertain).toBe(false)
    expect(v.fileToken).toBe('')
  })
  it('faceResult=UNCERTAIN → uncertain=true', () => {
    const v = toCaptureView({ faceResult: 'UNCERTAIN' })
    expect(v.uncertain).toBe(true)
  })
  it('reason 缺失 → 文案回退「未知」', () => {
    expect(toCaptureView({}).reasonLabel).toBe('未知')
  })
  it('confidence 字符串转数字 / 缺失置 null', () => {
    expect(toCaptureView({ confidence: '0.6' }).confidence).toBe(0.6)
    expect(toCaptureView({}).confidence).toBe(null)
  })
  it('expireAt 缺失 → expired=false', () => {
    expect(toCaptureView({}).expired).toBe(false)
  })
})

describe('isExpired', () => {
  const now = new Date('2026-01-01T00:00:00Z')
  it('过去 → true', () => {
    expect(isExpired('2020-01-01T00:00:00Z', now)).toBe(true)
  })
  it('未来 → false', () => {
    expect(isExpired('2030-01-01T00:00:00Z', now)).toBe(false)
  })
  it('缺失 → false', () => {
    expect(isExpired('', now)).toBe(false)
  })
})

describe('REASON_LABEL / FACE_RESULT_LABEL', () => {
  it('reason 文案', () => {
    expect(REASON_LABEL.NORMAL).toBe('正常服药')
    expect(REASON_LABEL.MANUAL).toBe('主动留证')
  })
  it('faceResult 三态文案', () => {
    expect(FACE_RESULT_LABEL.TAKEN).toBe('已服')
    expect(FACE_RESULT_LABEL.ABSENT).toBe('未服')
    expect(FACE_RESULT_LABEL.UNCERTAIN).toBe('待确认')
  })
})

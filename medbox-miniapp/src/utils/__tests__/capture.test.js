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

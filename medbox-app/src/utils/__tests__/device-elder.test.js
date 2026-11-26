import { describe, expect, it } from 'vitest'
import { buildElderProfiles, collectElderIds, deviceElderLabel } from '@/utils/device-elder.js'

describe('device-elder —— 设备列表老人区分', () => {
  it('collectElderIds 去重、去空白并忽略空值，保留首次出现顺序', () => {
    const devices = [
      { deviceId: 'BOXA1001', elderId: 'u-1001' },
      { deviceId: 'BOXA1002', elderId: ' u-1002 ' },
      { deviceId: 'BOXA1003', elderId: 'u-1001' },
      { deviceId: 'BOXA1004', elderId: '' },
      { deviceId: 'BOXA1005' },
    ]
    expect(collectElderIds(devices)).toEqual(['u-1001', 'u-1002'])
  })

  it('collectElderIds 容忍 undefined / null', () => {
    expect(collectElderIds()).toEqual([])
    expect(collectElderIds(null)).toEqual([])
  })

  it('buildElderProfiles 把 fulfilled/rejected 映射成 profile|null', () => {
    const ids = ['u-1001', 'u-1002', 'u-1003']
    const results = [
      { status: 'fulfilled', value: { userId: 'u-1001', name: '张桂英' } },
      { status: 'rejected' },
      { status: 'fulfilled', value: null },
    ]
    expect(buildElderProfiles(ids, results)).toEqual({
      'u-1001': { userId: 'u-1001', name: '张桂英' },
      'u-1002': null,
      'u-1003': null,
    })
  })

  it('deviceElderLabel 有姓名显示「姓名（ID）」，失败回退原始 ID，无归属显示药箱主控', () => {
    const profiles = { 'u-1001': { name: '张桂英' }, 'u-1002': null }
    expect(deviceElderLabel({ elderId: 'u-1001' }, profiles)).toBe('老人 张桂英（u-1001）')
    expect(deviceElderLabel({ elderId: 'u-1002' }, profiles)).toBe('老人 u-1002')
    expect(deviceElderLabel({ elderId: 'u-1003' }, profiles)).toBe('老人 u-1003')
    expect(deviceElderLabel({ deviceId: 'BOXA1001' }, profiles)).toBe('药箱主控')
  })
})

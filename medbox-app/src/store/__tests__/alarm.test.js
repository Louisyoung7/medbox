import { beforeEach, describe, expect, it, vi } from 'vitest'

const getUnreadCount = vi.fn()
const getAlarms = vi.fn()

vi.mock('@/api/alarm.js', () => ({
  getUnreadCount: (...args) => getUnreadCount(...args),
  getAlarms: (...args) => getAlarms(...args),
}))
vi.mock('@/store/user.js', () => ({ getUserInfo: () => null }))
vi.mock('@/api/device.js', () => ({ listDevices: async () => [] }))

async function loadStore() {
  vi.resetModules()
  return import('@/store/alarm.js')
}

beforeEach(() => {
  getUnreadCount.mockReset()
  getAlarms.mockReset()
  getUnreadCount.mockResolvedValue({ count: 3 })
  getAlarms.mockResolvedValue({ list: [{ alarmId: 'a-1' }], total: 1 })
})

describe('回前台补拉（feat/app-runtime）', () => {
  it('没有当前老人时直接返回，不发请求', async () => {
    const store = await loadStore()
    const latest = await store.refreshAfterForeground()
    expect(latest).toEqual([])
    expect(getUnreadCount).not.toHaveBeenCalled()
    expect(getAlarms).not.toHaveBeenCalled()
  })

  it('同时刷新未读角标与最新记录，并推进 refreshTick', async () => {
    const store = await loadStore()
    store.setCurrentElderId('u-1001')
    const tick = store.getAlarmState().refreshTick
    const latest = await store.refreshAfterForeground()
    expect(getUnreadCount).toHaveBeenCalledWith({ elderId: 'u-1001' })
    expect(getAlarms).toHaveBeenCalledWith({ elderId: 'u-1001', page: 1, size: 5 })
    expect(store.getAlarmState().unreadCount).toBe(3)
    expect(latest).toEqual([{ alarmId: 'a-1' }])
    expect(store.getAlarmState().refreshTick).toBe(tick + 1)
  })

  it('拉列表失败时保留上一次 latest（角标仍更新）', async () => {
    const store = await loadStore()
    store.setCurrentElderId('u-1001')
    await store.refreshAfterForeground()
    getAlarms.mockRejectedValue({ code: -1 })
    const latest = await store.refreshAfterForeground()
    expect(latest).toEqual([{ alarmId: 'a-1' }])
    expect(store.getAlarmState().unreadCount).toBe(3)
  })
})

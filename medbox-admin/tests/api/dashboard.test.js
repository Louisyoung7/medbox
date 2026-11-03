import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../src/api/request', () => ({
  default: { get: vi.fn() },
}))

import request from '../../src/api/request'
import { getOverview, getDevices, getAdherence, getMessages } from '../../src/api/dashboard'

describe('dashboard api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getOverview 调用 /admin/dashboard/overview 无参', async () => {
    request.get.mockResolvedValue({})
    await getOverview()
    expect(request.get).toHaveBeenCalledWith('/admin/dashboard/overview')
  })

  it('getDevices 默认 limit=20，透传 limit 查询', async () => {
    request.get.mockResolvedValue([])
    await getDevices()
    expect(request.get).toHaveBeenCalledWith('/admin/dashboard/devices', { params: { limit: 20 } })

    request.get.mockClear()
    await getDevices(50)
    expect(request.get).toHaveBeenCalledWith('/admin/dashboard/devices', { params: { limit: 50 } })
  })

  it('getAdherence 默认 granularity=week，可切 month', async () => {
    request.get.mockResolvedValue({ trend: [], missByHour: [], byElder: [] })
    await getAdherence()
    expect(request.get).toHaveBeenCalledWith('/admin/dashboard/adherence', { params: { granularity: 'week' } })

    request.get.mockClear()
    await getAdherence('month')
    expect(request.get).toHaveBeenCalledWith('/admin/dashboard/adherence', { params: { granularity: 'month' } })
  })

  it('getMessages 调用 /admin/dashboard/messages 无参', async () => {
    request.get.mockResolvedValue({ upTotal: 0, upLastMinute: 0, downTotal: 0, downLastMinute: 0 })
    const res = await getMessages()
    expect(request.get).toHaveBeenCalledWith('/admin/dashboard/messages')
    expect(res.upTotal).toBe(0)
  })
})

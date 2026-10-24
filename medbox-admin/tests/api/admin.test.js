import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../src/api/request', () => ({
  default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}))

import request from '../../src/api/request'
import { listUsers, deleteUser } from '../../src/api/admin'

describe('admin api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('listUsers 拼装查询参数并回传 PageResult', async () => {
    const pageResult = { list: [{ userId: 'u-1' }], total: 1, page: 1, size: 20 }
    request.get.mockResolvedValue(pageResult)

    const res = await listUsers({
      page: 1,
      size: 20,
      status: 'DELETED',
      keyword: 'abc',
      role: 'ELDER',
    })

    expect(request.get).toHaveBeenCalledWith('/admin/users', {
      params: { page: 1, size: 20, status: 'DELETED', keyword: 'abc', role: 'ELDER' },
    })
    expect(res).toEqual(pageResult)
    expect(res.list).toHaveLength(1)
    expect(res.total).toBe(1)
  })

  it('deleteUser 调用正确的 URL', async () => {
    request.delete.mockResolvedValue(undefined)
    await deleteUser('u-1001')
    expect(request.delete).toHaveBeenCalledWith('/admin/users/u-1001')
  })

  it('deleteUser 对含特殊字符的 userId 做 encodeURIComponent', async () => {
    request.delete.mockResolvedValue(undefined)
    await deleteUser('u/1001@x')
    expect(request.delete).toHaveBeenCalledWith('/admin/users/u%2F1001%40x')
  })
})

import { describe, it, expect, vi, beforeEach } from 'vitest'

// 用假请求实例替换真实 axios 封装，验证 api 层的 URL 拼装与 data 透传。
vi.mock('../../src/api/request', () => ({
  default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}))

import request from '../../src/api/request'
import { login } from '../../src/api/auth'

describe('auth api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('login 调 POST /auth/login 并回传 data', async () => {
    request.post.mockResolvedValue({
      token: 't',
      role: 'ADMIN',
      userId: 'u-1',
      refreshToken: 'r',
      expiresIn: 7200,
    })
    const data = await login('admin', 'public')
    expect(request.post).toHaveBeenCalledWith('/auth/login', {
      account: 'admin',
      password: 'public',
    })
    expect(data.role).toBe('ADMIN')
  })
})

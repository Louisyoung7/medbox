/**
 * "我的申请"本地缓存的编排测试（`feat/mp-guardian`，`src/store/guardian.js`）。
 *
 * <p>这个缓存是监护人侧**唯一**能看到自己发起过哪些申请的地方（后端没有对应接口），
 * 它的增删查错了不会报错、只会让用户看到幽灵申请或丢失撤销入口，所以逐条钉死：
 *
 * - 写入：申请响应落库、最新在前、同 elderId / 同 relationId 去重、缺省字段兜底；
 * - 读取：损坏 JSON / 非数组 / 缺 relationId 的脏数据一律按空列表或过滤，**不抛异常**；
 * - 清理：`clearMyApplications` 清空；`store/auth.js` 的 `logout()`（登出）必须带着清
 *   —— 换账号登录不该看到上一个监护人的申请。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const storage = {}
let launches = []

beforeEach(() => {
  launches = []
  Object.keys(storage).forEach((key) => delete storage[key])
  vi.stubGlobal('uni', {
    getStorageSync: (key) => (key in storage ? storage[key] : ''),
    setStorageSync: (key, value) => {
      storage[key] = value
    },
    removeStorageSync: (key) => {
      delete storage[key]
    },
    showToast: vi.fn(),
    showLoading: vi.fn(),
    hideLoading: vi.fn(),
    reLaunch: (opt) => launches.push(opt || {}),
    request: () => {},
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** 取全新的 store 模块（模块级无状态，但存储是共享的假对象，逐用例清）。 */
async function loadStore() {
  vi.resetModules()
  return import('@/store/guardian.js')
}

const APP_KEY = 'guardianApplications'

describe('guardian store —— 读写', () => {
  it('空存储读为空列表', async () => {
    const store = await loadStore()
    expect(store.loadMyApplications()).toEqual([])
  })

  it('addMyApplication：写入并返回（申请响应原样入缓存）', async () => {
    const store = await loadStore()
    const list = store.addMyApplication({
      relationId: 'gr-9',
      elderId: 'u-1001',
      status: 'PENDING',
      createdAt: '2026-10-23T08:00:00Z',
    })

    expect(list).toHaveLength(1)
    expect(list[0]).toEqual({ relationId: 'gr-9', elderId: 'u-1001', status: 'PENDING', createdAt: '2026-10-23T08:00:00Z' })
    // 真的落盘了：重新加载仍在
    expect(store.loadMyApplications()).toEqual(list)
    expect(JSON.parse(storage[APP_KEY])).toHaveLength(1)
  })

  it('addMyApplication：最新在前', async () => {
    const store = await loadStore()
    store.addMyApplication({ relationId: 'gr-1', elderId: 'u-1001', createdAt: '2026-10-20T08:00:00Z' })
    const list = store.addMyApplication({ relationId: 'gr-2', elderId: 'u-1002', createdAt: '2026-10-23T08:00:00Z' })

    expect(list.map((item) => item.relationId)).toEqual(['gr-2', 'gr-1'])
  })

  it('addMyApplication：同 elderId 去重（一个监护人对同一老人只留一条，取最新）', async () => {
    const store = await loadStore()
    store.addMyApplication({ relationId: 'gr-old', elderId: 'u-1001', createdAt: '2026-10-20T08:00:00Z' })
    const list = store.addMyApplication({ relationId: 'gr-new', elderId: 'u-1001', createdAt: '2026-10-23T08:00:00Z' })

    expect(list).toHaveLength(1)
    expect(list[0].relationId).toBe('gr-new')
  })

  it('addMyApplication：同 relationId 重复调用不叠加（服务端 REVOKED 复活同 ID）', async () => {
    const store = await loadStore()
    store.addMyApplication({ relationId: 'gr-4', elderId: 'u-1001' })
    store.addMyApplication({ relationId: 'gr-4', elderId: 'u-1001' })

    expect(store.loadMyApplications()).toHaveLength(1)
  })

  it('addMyApplication：无效输入（缺 relationId）不写入、不抛异常', async () => {
    const store = await loadStore()
    const list = store.addMyApplication({ elderId: 'u-1001' })

    expect(list).toEqual([])
    expect(storage[APP_KEY]).toBeUndefined()
  })

  it('addMyApplication：status / createdAt 缺省兜底（PENDING / 空串）', async () => {
    const store = await loadStore()
    const list = store.addMyApplication({ relationId: 'gr-9', elderId: 'u-1001' })

    expect(list[0].status).toBe('PENDING')
    expect(list[0].createdAt).toBe('')
  })

  it('removeMyApplication：按 relationId 移除，不影响其他条目', async () => {
    const store = await loadStore()
    store.addMyApplication({ relationId: 'gr-1', elderId: 'u-1001' })
    store.addMyApplication({ relationId: 'gr-2', elderId: 'u-1002' })
    const list = store.removeMyApplication('gr-1')

    expect(list.map((item) => item.relationId)).toEqual(['gr-2'])
    expect(store.loadMyApplications()).toHaveLength(1)
  })

  it('removeMyApplication：移除不存在的 ID 静默成功', async () => {
    const store = await loadStore()
    store.addMyApplication({ relationId: 'gr-1', elderId: 'u-1001' })
    const list = store.removeMyApplication('gr-9999')

    expect(list).toHaveLength(1)
  })

  it('clearMyApplications：清空', async () => {
    const store = await loadStore()
    store.addMyApplication({ relationId: 'gr-1', elderId: 'u-1001' })
    store.clearMyApplications()

    expect(store.loadMyApplications()).toEqual([])
    expect(storage[APP_KEY]).toBeUndefined()
  })
})

describe('guardian store —— 脏数据容错', () => {
  it('存储损坏（非法 JSON）按空列表，不抛异常', async () => {
    storage[APP_KEY] = '{not json'
    const store = await loadStore()

    expect(store.loadMyApplications()).toEqual([])
  })

  it('非数组 JSON 按空列表', async () => {
    storage[APP_KEY] = JSON.stringify({ relationId: 'gr-1' })
    const store = await loadStore()

    expect(store.loadMyApplications()).toEqual([])
  })

  it('过滤缺 relationId 的脏元素，补齐缺省字段', async () => {
    storage[APP_KEY] = JSON.stringify([
      { relationId: 'gr-1', elderId: 'u-1001', status: 'PENDING', createdAt: '2026-10-23T08:00:00Z' },
      { elderId: 'u-1002' },
      null,
      'x',
    ])
    const store = await loadStore()

    expect(store.loadMyApplications()).toEqual([
      { relationId: 'gr-1', elderId: 'u-1001', status: 'PENDING', createdAt: '2026-10-23T08:00:00Z' },
    ])
  })
})

describe('guardian store —— 登出清理（与 auth.js 的接线）', () => {
  it('logout() 清空申请缓存并跳登录页', async () => {
    vi.resetModules()
    const store = await import('@/store/guardian.js')
    store.addMyApplication({ relationId: 'gr-1', elderId: 'u-1001' })
    expect(store.loadMyApplications()).toHaveLength(1)

    // auth.js 与 guardian.js 互相独立，重新加载模块树后调 logout（不发起任何网络请求）
    vi.resetModules()
    const auth = await import('@/store/auth.js')
    auth.logout()

    expect(storage[APP_KEY]).toBeUndefined()
    expect(launches).toHaveLength(1)
    expect(launches[0].url).toBe('/pages/auth/login')
  })
})

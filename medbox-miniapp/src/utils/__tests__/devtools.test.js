import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * 计划时间槽计数器（`takeNextSlotOffset` / `readSlotSeq`）。
 *
 * <p>存在的理由：后端 `med_record` 有 `(plan_item_id, plan_time)` 部分唯一索引，
 * 同槽只落一条，而 devtools 种子只有 1 个计划项 × 每日 08:00 —— 槽位有限。
 * 计数器每次发送前移一天并**持久化**，否则刷新小程序后归零又会撞已占用的槽。
 */

/**
 * 调试页薄封装单测（`feat/mp-devtools`，对应 medbox-spec/08 第四章）。
 *
 * <p>被测的 `utils/devtools.js` 依赖 `src/config/index.js`，后者在**模块加载时**就读
 * `uni.getStorageSync` 算 `BASE_URL` / `WS_URL`，所以沿用 config 测试的做法：
 * 每个用例先重置模块注册表 + 打桩 `uni`（内存 storage），再**动态 import**。
 */

/** 用内存对象模拟 uni 的同步 storage（与 `config/__tests__/index.test.js` 同款）。 */
function stubUni(initial = {}) {
  const data = { ...initial }
  vi.stubGlobal('uni', {
    getStorageSync: (key) => (key in data ? data[key] : ''),
    setStorageSync: (key, value) => {
      data[key] = value
    },
    removeStorageSync: (key) => {
      delete data[key]
    },
  })
  return data
}

/**
 * 重新加载被测模块。
 * @param {Object} [storage] 本地缓存初值（模拟"上次设置的 serverHost"）
 * @returns {Promise<{devtools: Object, data: Object}>}
 */
async function loadDevtools(storage) {
  vi.resetModules()
  const data = stubUni(storage)
  // eslint-disable-next-line no-undef
  const devtools = await import('@/utils/devtools.js')
  return { devtools, data }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('readServerHost', () => {
  it('未设置过时返回空串（页面据此显示编译期默认值）', async () => {
    const { devtools } = await loadDevtools()
    expect(devtools.readServerHost()).toBe('')
  })

  it('返回本地缓存里已覆盖的 HOST', async () => {
    const { devtools } = await loadDevtools({ serverHost: '10.0.0.7' })
    expect(devtools.readServerHost()).toBe('10.0.0.7')
  })

  it('缓存值非字符串（如被写成其它类型）时兜底空串，不外泄到 UI', async () => {
    const { devtools } = await loadDevtools({ serverHost: 123 })
    expect(devtools.readServerHost()).toBe('')
  })
})

describe('saveServerHost', () => {
  it('合法输入：归一化后写入缓存并返回 true', async () => {
    const { devtools, data } = await loadDevtools()
    expect(devtools.saveServerHost('http://192.168.1.23:8080/medbox/')).toBe(true)
    expect(data.serverHost).toBe('192.168.1.23')
  })

  it('非法输入：不写缓存并返回 false（页面据此提示重填）', async () => {
    const { devtools, data } = await loadDevtools()
    expect(devtools.saveServerHost('')).toBe(false)
    expect(devtools.saveServerHost('   ')).toBe(false)
    expect(devtools.saveServerHost('http://')).toBe(false)
    expect(devtools.saveServerHost(undefined)).toBe(false)
    expect(data.serverHost).toBeUndefined()
  })

  it('已存的值不会被非法输入冲掉', async () => {
    const { devtools, data } = await loadDevtools({ serverHost: '192.168.1.23' })
    expect(devtools.saveServerHost('http://')).toBe(false)
    expect(data.serverHost).toBe('192.168.1.23')
  })
})

describe('resetServerHost', () => {
  it('清掉运行时覆盖，回落到编译期默认值', async () => {
    const { devtools, data } = await loadDevtools({ serverHost: '192.168.1.23' })
    devtools.resetServerHost()
    expect(data.serverHost).toBeUndefined()
    expect(devtools.readServerHost()).toBe('')
  })

  it('没有覆盖时调用也无副作用（幂等）', async () => {
    const { devtools } = await loadDevtools()
    expect(() => devtools.resetServerHost()).not.toThrow()
    expect(devtools.readServerHost()).toBe('')
  })
})

describe('takeNextSlotOffset / readSlotSeq', () => {
  it('首次取到 0（今天），之后逐日前移', async () => {
    const { devtools } = await loadDevtools()
    expect(devtools.takeNextSlotOffset()).toBe(0)
    expect(devtools.takeNextSlotOffset()).toBe(-1)
    expect(devtools.takeNextSlotOffset()).toBe(-2)
  })

  it('计数器写进本地缓存：刷新小程序后不归零（否则又撞已占用的槽）', async () => {
    const { devtools, data } = await loadDevtools()
    devtools.takeNextSlotOffset()
    devtools.takeNextSlotOffset()
    expect(data.simSlotSeq).toBe(2)
    expect(devtools.readSlotSeq()).toBe(2)
    // 重新加载模块（模拟重启小程序）：计数器从缓存恢复，继续往后要新槽
    vi.resetModules()
    const reloaded = await import('@/utils/devtools.js')
    expect(reloaded.takeNextSlotOffset()).toBe(-2)
  })

  it('每次调用都返回新值：即使上一条被后端幂等吃掉，也不会重复撞同一个槽', async () => {
    const { devtools } = await loadDevtools()
    const seen = [0, 1, 2, 3, 4].map(() => devtools.takeNextSlotOffset())
    expect(new Set(seen).size).toBe(5)
  })

  it('缓存里的值非法（非数字）时按 0 起步，不 NaN', async () => {
    const { devtools } = await loadDevtools({ simSlotSeq: 'abc' })
    expect(devtools.readSlotSeq()).toBe(0)
    expect(devtools.takeNextSlotOffset()).toBe(0)
  })
})

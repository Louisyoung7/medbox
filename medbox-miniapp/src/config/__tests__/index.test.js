import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * 配置出口单测（`feat/mp-server-config`，对应 medbox-spec/01 的 4.2 / 4.5）。
 *
 * <p>**必须动态 import**：`src/config/index.js` 在**模块加载时**就算好 `BASE_URL` / `WS_URL`
 * （业务代码依赖这两个常量），静态 import 会在 `uni` 打桩之前执行而报 `uni is not defined`。
 * 因此每个用例先重置模块注册表 + 打桩 `uni`，再 `await import(...)` 拿到新实例。
 */

/** 用内存对象模拟 uni 的同步 storage（`uni.getStorageSync` 系列）。 */
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
 * 重新加载配置模块。
 * @param {Object} [storage] 本地缓存初值（模拟"上次设置的 serverHost"）
 * @param {Object} [options]
 * @param {string} [options.platform] 模拟 `process.env.UNI_PLATFORM`（h5 / mp-weixin ...）
 * @param {Object} [options.env] 额外注入的编译期环境变量（VITE_BASE_URL ...）
 * @returns {Promise<{config: Object, data: Object}>}
 */
async function loadConfig(storage, options = {}) {
  vi.resetModules()
  const data = stubUni(storage)
  if (options.platform) {
    process.env.UNI_PLATFORM = options.platform
  }
  // 先显式清空 VITE_BASE_URL，再套用 options.env 覆盖。
  //
  // <p>**为什么必须清**：Vitest 会把本机 `.env.local` 注入 `import.meta.env`，
  // 而 H5 反代联调的 `.env.local` 里就写着 `VITE_BASE_URL=/medbox/api/v1` ——
  // 不显式清，"H5 但未配 VITE_BASE_URL"这条用例在**本机必红**（CI 没有 `.env.local`，
  // 所以只有本地红，极易被误判成"代码写错了"）。清成空串与"真的没配"等价
  // （`config/index.js` 里 `IS_H5 ? import.meta.env.VITE_BASE_URL : ''` 取到 '' 即回落）。
  vi.stubEnv('VITE_BASE_URL', '')
  Object.entries(options.env || {}).forEach(([key, value]) => vi.stubEnv(key, value))
  // eslint-disable-next-line no-undef
  const config = await import('@/config/index.js')
  return { config, data }
}

afterEach(() => {
  vi.unstubAllEnvs()
  delete process.env.UNI_PLATFORM
})

describe('normalizeHost', () => {
  /** normalizeHost 是纯函数，直接取一份模块用即可（不读 uni 缓存的值）。 */
  const normalize = async (...args) => {
    const { config } = await loadConfig()
    return config.normalizeHost(...args)
  }

  it('裸 IP / 域名：原样返回', async () => {
    expect(await normalize('192.168.1.23')).toBe('192.168.1.23')
    expect(await normalize('medbox.local')).toBe('medbox.local')
    expect(await normalize('127.0.0.1')).toBe('127.0.0.1')
  })

  it('去掉协议（http / https，含漏敲斜杠的写法）', async () => {
    expect(await normalize('http://192.168.1.23')).toBe('192.168.1.23')
    expect(await normalize('https://192.168.1.23')).toBe('192.168.1.23')
    expect(await normalize('HTTP://192.168.1.23')).toBe('192.168.1.23')
    expect(await normalize('http:192.168.1.23')).toBe('192.168.1.23')
  })

  it('去掉端口：端口固定 8080，HOST 里不许再带一个', async () => {
    expect(await normalize('192.168.1.23:8080')).toBe('192.168.1.23')
    expect(await normalize('http://192.168.1.23:8080')).toBe('192.168.1.23')
    // 全角冒号：中文输入法下的高频误输入
    expect(await normalize('192.168.1.23：8080')).toBe('192.168.1.23')
  })

  it('去掉路径 / query / hash：容忍粘贴浏览器地址栏整串', async () => {
    expect(await normalize('192.168.1.23/medbox/api/v1')).toBe('192.168.1.23')
    expect(await normalize('http://192.168.1.23:8080/medbox/')).toBe('192.168.1.23')
    expect(await normalize('192.168.1.23?a=1#top')).toBe('192.168.1.23')
  })

  it('容忍空格与前导斜杠', async () => {
    expect(await normalize('  192.168.1.23  ')).toBe('192.168.1.23')
    expect(await normalize('//192.168.1.23')).toBe('192.168.1.23')
  })

  it('非法输入返回空串：非字符串 / 空白 / 纯协议', async () => {
    expect(await normalize('')).toBe('')
    expect(await normalize('   ')).toBe('')
    expect(await normalize('http://')).toBe('')
    expect(await normalize(undefined)).toBe('')
    expect(await normalize(null)).toBe('')
    expect(await normalize(192168)).toBe('')
  })
})

describe('HOST 取值链', () => {
  it('没配 VITE_SERVER_HOST 也没本地缓存时兜底 127.0.0.1', async () => {
    const { config } = await loadConfig()
    expect(config.BASE_URL).toContain('127.0.0.1:8080/medbox/api/v1')
    expect(config.WS_URL).toContain('127.0.0.1:8080/medbox/ws')
  })

  it('本地缓存优先：serverHost 覆盖编译期默认值', async () => {
    const { config } = await loadConfig({ serverHost: '10.0.0.7' })
    expect(config.BASE_URL).toContain('10.0.0.7:8080/medbox/api/v1')
    expect(config.WS_URL).toContain('10.0.0.7:8080/medbox/ws')
    expect(config.SERVER_HOST).toBe('10.0.0.7')
  })

  it('BASE_URL 与 WS_URL 共用同一个 HOST', async () => {
    const { config } = await loadConfig({ serverHost: '192.168.1.23' })
    const baseHost = new URL(config.BASE_URL).host
    expect(new URL(config.WS_URL).host).toBe(baseHost)
  })
})

describe('VITE_BASE_URL 的平台隔离（H5 专属）', () => {
  it('H5 平台：override 生效，REST 走同源相对路径（绕开 CORS）', async () => {
    const { config } = await loadConfig({}, {
      platform: 'h5',
      env: { VITE_BASE_URL: '/medbox/api/v1' },
    })
    expect(config.BASE_URL).toBe('/medbox/api/v1')
  })

  it('H5 但未配 VITE_BASE_URL：回落到绝对地址', async () => {
    const { config } = await loadConfig({}, { platform: 'h5' })
    expect(config.BASE_URL).toContain('127.0.0.1:8080/medbox/api/v1')
  })

  it('微信小程序：配了 VITE_BASE_URL 也必须忽略 —— 否则会请求到小程序自己的 origin', async () => {
    const { config } = await loadConfig({}, {
      platform: 'mp-weixin',
      env: { VITE_BASE_URL: '/medbox/api/v1' },
    })
    expect(config.BASE_URL).toContain('127.0.0.1:8080/medbox/api/v1')
    expect(config.BASE_URL.startsWith('http')).toBe(true)
  })

  it('App 端同样忽略 override', async () => {
    const { config } = await loadConfig({}, {
      platform: 'app',
      env: { VITE_BASE_URL: '/medbox/api/v1' },
    })
    expect(config.BASE_URL).toContain('127.0.0.1:8080/medbox/api/v1')
  })

  it('WS_URL 永远绝对：WebSocket 不走 dev server 反代（不受 CORS 限制）', async () => {
    const { config } = await loadConfig({}, {
      platform: 'h5',
      env: { VITE_BASE_URL: '/medbox/api/v1' },
    })
    expect(config.WS_URL).toBe('ws://127.0.0.1:8080/medbox/ws')
  })

  it('配了 override 但不在 H5：给出明确告警，避免"配了没生效"的困惑', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    await loadConfig({}, {
      platform: 'mp-weixin',
      env: { VITE_BASE_URL: '/medbox/api/v1' },
    })
    expect(warn).toHaveBeenCalled()
    expect(warn.mock.calls[0][0]).toContain('VITE_BASE_URL 只在 H5 生效')
    warn.mockRestore()
  })
})

describe('setServerHost / clearServerHost', () => {
  it('写入前先归一化：粘贴整串地址也只存裸 HOST', async () => {
    const { config, data } = await loadConfig()
    expect(config.setServerHost('http://192.168.1.23:8080/medbox/')).toBe(true)
    expect(data.serverHost).toBe('192.168.1.23')
  })

  it('非法输入不写缓存并返回 false（写空串会被 || 静默忽略，看起来"改了没生效"）', async () => {
    const { config, data } = await loadConfig()
    expect(config.setServerHost('')).toBe(false)
    expect(config.setServerHost('   ')).toBe(false)
    expect(data.serverHost).toBeUndefined()
  })

  it('已存的值不会被非法输入冲掉', async () => {
    const { config, data } = await loadConfig({ serverHost: '192.168.1.23' })
    expect(config.setServerHost('http://')).toBe(false)
    expect(data.serverHost).toBe('192.168.1.23')
  })

  it('clearServerHost 清掉运行时覆盖', async () => {
    const { config, data } = await loadConfig({ serverHost: '192.168.1.23' })
    config.clearServerHost()
    expect(data.serverHost).toBeUndefined()
  })
})

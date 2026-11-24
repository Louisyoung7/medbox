import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 检查更新编排单测（`feat/app-release`）。
 *
 * <p>只测 `store/update.js` 的分支与副作用：H5 不发请求、有新版本才弹窗、下载走
 * `plus.runtime.openURL`、失败不打扰用户。请求层自身的行为由 `api` 测试覆盖。
 */

const getAppVersion = vi.fn()

vi.mock('@/api/app-version.js', () => ({
  getAppVersion: (...args) => getAppVersion(...args),
}))

let modal
let toasts

async function loadStore() {
  vi.resetModules()
  return import('@/store/update.js')
}

beforeEach(() => {
  getAppVersion.mockReset()
  modal = null
  toasts = []
  vi.unstubAllGlobals()
  vi.stubGlobal('uni', {
    getSystemInfoSync: () => ({ appVersion: '1.0.0', appVersionCode: 100 }),
    showModal: (options) => {
      modal = options
    },
    showToast: (options) => {
      toasts.push(options)
    },
  })
})

describe('checkUpdate', () => {
  it('非 App（H5）直接返回 unsupported，不发请求', async () => {
    const store = await loadStore()
    const result = await store.checkUpdate()
    expect(result.status).toBe('unsupported')
    expect(getAppVersion).not.toHaveBeenCalled()
  })

  it('带本机 versionCode 查询，落后时返回 available', async () => {
    vi.stubGlobal('plus', { runtime: {} })
    getAppVersion.mockResolvedValue({
      latestVersionName: '1.0.1',
      latestVersionCode: 101,
      downloadUrl: 'http://192.168.1.2:8080/medbox/app/a.apk',
      upToDate: false,
    })
    const store = await loadStore()
    const result = await store.checkUpdate()
    expect(getAppVersion).toHaveBeenCalledWith(100, { silent: true })
    expect(result.status).toBe('available')
    expect(result.info.latestVersionCode).toBe(101)
  })

  it('已是最新时返回 latest', async () => {
    vi.stubGlobal('plus', { runtime: {} })
    getAppVersion.mockResolvedValue({ latestVersionCode: 100, upToDate: true })
    const store = await loadStore()
    expect((await store.checkUpdate()).status).toBe('latest')
  })

  it('接口失败返回 failed，不抛错', async () => {
    vi.stubGlobal('plus', { runtime: {} })
    getAppVersion.mockRejectedValue({ code: -1, message: '网络异常' })
    const store = await loadStore()
    const result = await store.checkUpdate()
    expect(result.status).toBe('failed')
    expect(result.error.code).toBe(-1)
  })
})

describe('promptUpdate', () => {
  it('确认后打开下载地址', async () => {
    const openURL = vi.fn()
    vi.stubGlobal('plus', { runtime: { openURL } })
    const store = await loadStore()
    store.promptUpdate({
      latestVersionName: '1.0.1',
      downloadUrl: 'http://192.168.1.2:8080/medbox/app/a.apk',
      releaseNotes: '修复提醒延迟',
    })
    expect(modal.title).toContain('1.0.1')
    expect(modal.content).toBe('修复提醒延迟')
    modal.success({ confirm: true })
    expect(openURL).toHaveBeenCalledWith('http://192.168.1.2:8080/medbox/app/a.apk')
    expect(toasts).toEqual([])
  })

  it('取消不下载', async () => {
    const openURL = vi.fn()
    vi.stubGlobal('plus', { runtime: { openURL } })
    const store = await loadStore()
    store.promptUpdate({ latestVersionName: '1.0.1', downloadUrl: 'http://x/a.apk' })
    modal.success({ confirm: false })
    expect(openURL).not.toHaveBeenCalled()
  })

  it('下载地址缺失时提示而不是静默', async () => {
    vi.stubGlobal('plus', { runtime: { openURL: vi.fn() } })
    const store = await loadStore()
    store.promptUpdate({ latestVersionName: '1.0.1' })
    modal.success({ confirm: true })
    expect(toasts[0].title).toContain('下载地址不可用')
  })
})

describe('checkAndPromptUpdate', () => {
  it('启动检查：有更新才弹窗，失败静默', async () => {
    vi.stubGlobal('plus', { runtime: {} })
    const store = await loadStore()

    getAppVersion.mockResolvedValueOnce({ latestVersionCode: 100, upToDate: true })
    await store.checkAndPromptUpdate()
    expect(modal).toBeNull()

    getAppVersion.mockRejectedValueOnce({ code: -1 })
    await store.checkAndPromptUpdate()
    expect(modal).toBeNull()
    expect(toasts).toEqual([])

    getAppVersion.mockResolvedValueOnce({
      latestVersionName: '1.0.1',
      latestVersionCode: 101,
      upToDate: false,
    })
    await store.checkAndPromptUpdate()
    expect(modal.title).toContain('1.0.1')
  })
})

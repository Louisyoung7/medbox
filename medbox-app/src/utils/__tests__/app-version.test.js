import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * 版本工具单测（`feat/app-release`）。
 *
 * <p>覆盖 02j §1.1 的判定口径：只有 `latestVersionCode > 本机 versionCode` 才提示更新；
 * 本机版本读不到（H5 / 单测）时按 0 处理，避免 `upToDate=false` 造成的反复弹窗。
 */

async function loadUtils() {
  vi.resetModules()
  return import('@/utils/app-version.js')
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('getLocalVersion', () => {
  it('读取 appVersion / appVersionCode（App 端口径）', async () => {
    vi.stubGlobal('uni', {
      getSystemInfoSync: () => ({ appVersion: '1.0.0', appVersionCode: 100 }),
    })
    const { getLocalVersion } = await loadUtils()
    expect(getLocalVersion()).toEqual({ versionName: '1.0.0', versionCode: 100 })
  })

  it('App 信息缺失或异常时降级为 0，不抛错', async () => {
    vi.stubGlobal('uni', {
      getSystemInfoSync: () => ({ appVersion: '', appVersionCode: 'x' }),
    })
    const { getLocalVersion } = await loadUtils()
    expect(getLocalVersion()).toEqual({ versionName: '', versionCode: 0 })

    vi.stubGlobal('uni', {
      getSystemInfoSync: () => {
        throw new Error('boom')
      },
    })
    expect(getLocalVersion()).toEqual({ versionName: '', versionCode: 0 })
  })
})

describe('formatLocalVersion', () => {
  it('有 versionCode 时拼成 1.0.0（100），缺版本名时回“未知”', async () => {
    const { formatLocalVersion } = await loadUtils()
    expect(formatLocalVersion({ versionName: '1.0.0', versionCode: 100 })).toBe('1.0.0（100）')
    expect(formatLocalVersion({ versionName: '1.0.0', versionCode: 0 })).toBe('1.0.0')
    expect(formatLocalVersion(null)).toBe('未知')
  })
})

describe('hasUpdate', () => {
  it('latestVersionCode 大于本机才提示', async () => {
    const { hasUpdate } = await loadUtils()
    const local = { versionName: '1.0.0', versionCode: 100 }
    expect(hasUpdate({ latestVersionCode: 101, upToDate: false }, local)).toBe(true)
    expect(hasUpdate({ latestVersionCode: 100, upToDate: false }, local)).toBe(false)
    expect(hasUpdate({ latestVersionCode: 99, upToDate: false }, local)).toBe(false)
  })

  it('upToDate=true 或版本号非法时不提示', async () => {
    const { hasUpdate } = await loadUtils()
    const local = { versionName: '1.0.0', versionCode: 100 }
    expect(hasUpdate({ latestVersionCode: 101, upToDate: true }, local)).toBe(false)
    expect(hasUpdate({ latestVersionCode: 0, upToDate: false }, local)).toBe(false)
    expect(hasUpdate({ latestVersionCode: 'x', upToDate: false }, local)).toBe(false)
    expect(hasUpdate(null, local)).toBe(false)
  })

  it('本机 versionCode 未知（0）时，只要后端有版本号就提示', async () => {
    const { hasUpdate } = await loadUtils()
    expect(hasUpdate({ latestVersionCode: 100, upToDate: false }, { versionCode: 0 })).toBe(true)
  })
})

describe('updateContent', () => {
  it('优先用 releaseNotes，空白时给默认文案', async () => {
    const { updateContent } = await loadUtils()
    expect(updateContent({ releaseNotes: '  修复了提醒延迟  ' })).toBe('修复了提醒延迟')
    expect(updateContent({ releaseNotes: '   ' })).toContain('建议更新到最新版本')
    expect(updateContent(null)).toContain('建议更新到最新版本')
  })
})

describe('isAppRuntime / openDownloadUrl', () => {
  it('无 plus 判定为非 App；无 url 不跳转', async () => {
    const { isAppRuntime, openDownloadUrl } = await loadUtils()
    expect(isAppRuntime()).toBe(false)
    expect(openDownloadUrl('')).toBe(false)
  })

  it('App 端用 plus.runtime.openURL 交给系统浏览器', async () => {
    const openURL = vi.fn()
    vi.stubGlobal('plus', { runtime: { openURL } })
    const { isAppRuntime, openDownloadUrl } = await loadUtils()
    expect(isAppRuntime()).toBe(true)
    expect(openDownloadUrl('http://192.168.1.2:8080/medbox/app/a.apk')).toBe(true)
    expect(openURL).toHaveBeenCalledWith('http://192.168.1.2:8080/medbox/app/a.apk')
  })

  it('非 App 环境退化为 window.open', async () => {
    const open = vi.fn()
    vi.stubGlobal('window', { open })
    const { openDownloadUrl } = await loadUtils()
    expect(openDownloadUrl('http://example.com/a.apk')).toBe(true)
    expect(open).toHaveBeenCalledWith('http://example.com/a.apk', '_blank')
  })
})

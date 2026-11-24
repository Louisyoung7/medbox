import { readFileSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 发版配置自检（`feat/app-release`）。
 *
 * <p>图标 / 启动图 / 隐私弹窗 / versionCode 都是**出包时才暴露问题**的配置：路径写错、
 * 图片被删、版本号回退，CI 编译依旧能过。这里在单测阶段把这些硬约束钉住。
 */

const SRC = fileURLToPath(new URL('../../', import.meta.url))

/** manifest.json 是 JSONC（含块注释），去掉注释后按 JSON 解析。 */
function readManifest() {
  const raw = readFileSync(path.join(SRC, 'manifest.json'), 'utf8')
  return JSON.parse(raw.replace(/\/\*[\s\S]*?\*\//g, ''))
}

function readJson(relative) {
  return JSON.parse(readFileSync(path.join(SRC, relative), 'utf8'))
}

function fileSize(relative) {
  return statSync(path.join(SRC, relative)).size
}

/** 读 PNG 的 IHDR（宽高在固定偏移 16~24）。 */
function pngSize(relative) {
  const buf = readFileSync(path.join(SRC, relative))
  expect(buf.slice(1, 4).toString('ascii')).toBe('PNG')
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) }
}

const manifest = readManifest()
const android = manifest['app-plus'].distribute

describe('图标与启动图', () => {
  it('manifest 配置了 Android 各密度图标，且文件都存在', () => {
    const icons = android.icons.android
    expect(Object.keys(icons)).toEqual(
      expect.arrayContaining(['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi']),
    )
    for (const file of Object.values(icons)) {
      expect(fileSize(file)).toBeGreaterThan(0)
    }
    // 应用商店上传用的 512 图标不在 manifest 里，但发版必须留着
    expect(fileSize('static/app/icon-512.png')).toBeGreaterThan(0)
  })

  it('启动图是 .9.png 且分辨率正确（hdpi / xhdpi / xxhdpi）', () => {
    const splash = manifest['app-plus'].splashscreen.android
    const expected = {
      hdpi: [480, 762],
      xhdpi: [720, 1242],
      xxhdpi: [1080, 1882],
    }
    for (const [density, [width, height]] of Object.entries(expected)) {
      const file = splash[density]
      // Android 自定义启动图必须是 9-patch，否则云打包会提示格式不支持
      expect(file).toMatch(/\.9\.png$/)
      expect(pngSize(file)).toEqual({ width, height })
    }
  })
})

describe('版本号与包名', () => {
  it('versionCode 为递增的正整数，起点 100', () => {
    const code = String(manifest.versionCode)
    expect(code).toMatch(/^\d+$/)
    expect(Number(code)).toBeGreaterThanOrEqual(100)
  })

  it('versionName 为语义化版本', () => {
    expect(manifest.versionName).toMatch(/^\d+\.\d+\.\d+$/)
  })

  it('包名固定（改包名 = 无法覆盖升级）', () => {
    expect(android.android.packagename).toBe('com.medbox.app')
  })
})

describe('隐私弹窗', () => {
  const privacyLink = 'static/privacy.html'

  it('manifest 兜底模板链接本地隐私政策，文件存在且不是空壳', () => {
    const template = manifest['app-plus'].privacy.template
    expect(manifest['app-plus'].privacy.prompt).toBe('template')
    expect(template.message).toContain(privacyLink)
    expect(template.second.message).toContain(privacyLink)
    expect(fileSize(privacyLink)).toBeGreaterThan(2000)
  })

  it('androidPrivacy.json（HBuilderX 3.2.1+ 优先）与 manifest 同口径', () => {
    const privacy = readJson('androidPrivacy.json')
    expect(privacy.prompt).toBe('template')
    expect(String(privacy.version)).toMatch(/^\d+$/)
    expect(privacy.message).toContain(privacyLink)
    expect(privacy.second.buttonRefuse).toBeTruthy()
    expect(privacy.message).toBe(manifest['app-plus'].privacy.template.message)
  })

  it('隐私政策正文覆盖数据范围、存储位置与免责说明', () => {
    const html = readFileSync(path.join(SRC, privacyLink), 'utf8')
    expect(html).toContain('局域网')
    expect(html).toContain('不采集')
    expect(html).toContain('不是医疗器械')
  })
})

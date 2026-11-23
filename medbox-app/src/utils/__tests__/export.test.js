import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  EXPORT_FALLBACK_NAME,
  exportFilename,
  pickExportMode,
  saveExportedFile,
} from '@/utils/export.js'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('导出文件名与落地方式（feat/app-runtime）', () => {
  it('优先后端 filename，空值回落默认 CSV 名', () => {
    expect(exportFilename({ filename: 'report-张桂英.csv' })).toBe('report-张桂英.csv')
    expect(exportFilename({ filename: '  ' })).toBe(EXPORT_FALLBACK_NAME)
    expect(exportFilename(null)).toBe(EXPORT_FALLBACK_NAME)
  })

  it('App 优先下载目录，其次 H5 浏览器下载，都没有则 none', () => {
    expect(pickExportMode({ hasPlusIo: true, hasDocument: true })).toBe('app-downloads')
    expect(pickExportMode({ hasDocument: true })).toBe('h5-download')
    expect(pickExportMode({})).toBe('none')
  })

  it('H5：用 <a download> 触发下载', async () => {
    const clicked = []
    vi.stubGlobal('document', {
      createElement: () => ({
        style: {},
        set href(v) {},
        set download(v) {},
        click: () => clicked.push(1),
      }),
      body: { appendChild: () => {}, removeChild: () => {} },
    })
    const result = await saveExportedFile(
      { tempFilePath: 'blob:fake', filename: 'records.csv' },
      { hasDocument: true },
    )
    expect(result.mode).toBe('h5-download')
    expect(result.path).toBe('blob:fake')
    expect(clicked.length).toBe(1)
  })

  it('无落地能力或没有临时文件 → none，不抛错', async () => {
    expect((await saveExportedFile({ tempFilePath: 'blob:x' }, {})).mode).toBe('none')
    expect((await saveExportedFile({}, { hasDocument: true })).mode).toBe('none')
  })
})

/**
 * 报告域接口的请求层链路测试（`feat/mp-report`，对应 medbox-spec/02 第 7 章）。
 *
 * <p>与 `record.test.js` 同套打桩：stub `uni.request` / `uni.downloadFile`，断言路径、方法、
 * Bearer、query 透传、文件流兑现 `{tempFilePath,filePath,filename}` 与 40301/40302 错误码分流。
 *
 * <p>⚠️ 后端 `record-report` 已落地：这里断言的是**前端契约**（路径 / 方法 / 鉴权头 / 文件名解析 /
 * 错误码），真机联调如有偏差改契约处即可。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const storage = {}
let requests = []
let downloads = []
let scriptsReq = []
let scriptsDl = []
let dlErrorBody = '{}'

beforeEach(() => {
  requests = []
  downloads = []
  scriptsReq = []
  scriptsDl = []
  dlErrorBody = '{}'
  Object.keys(storage).forEach((key) => delete storage[key])
  vi.stubGlobal('uni', {
    getStorageSync: (key) => (key in storage ? storage[key] : ''),
    setStorageSync: (key, value) => {
      storage[key] = value
    },
    removeStorageSync: (key) => {
      delete storage[key]
    },
    showLoading: () => {},
    hideLoading: () => {},
    showToast: () => {},
    reLaunch: () => {},
    request: (options) => {
      requests.push(options)
      const script = scriptsReq.shift() || { body: { code: 0, data: null } }
      if (script.fail) {
        options.fail({ errMsg: 'request:fail' })
        return
      }
      options.success({ statusCode: script.status || 200, data: script.body })
    },
    downloadFile: (options) => {
      downloads.push(options)
      const script = scriptsDl.shift() || {
        statusCode: 200,
        tempFilePath: '/tmp/x.csv',
        filePath: '/save/x.csv',
        header: {},
      }
      // 非 2xx 时 downloadFile 会读临时文件里的 JSON 错误体，这里预先捕获供 getFileSystemManager 回放
      dlErrorBody = script.errorBody || '{}'
      if (script.fail) {
        options.fail({ errMsg: 'downloadFile:fail' })
        return
      }
      options.success({
        statusCode: script.statusCode,
        tempFilePath: script.tempFilePath,
        filePath: script.filePath,
        header: script.header || {},
      })
    },
    getFileSystemManager: () => ({
      readFile: ({ success }) => success({ data: dlErrorBody }),
    }),
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function loadReportApi({ token = 'TOKEN' } = {}) {
  if (token) storage.accessToken = token
  vi.resetModules()
  const request = await import('@/api/request.js')
  const report = await import('@/api/report.js')
  return { report, CODE: request.CODE }
}

describe('report —— 请求层', () => {
  it('getAdherence：GET /adherence 带 Bearer，elderId/period 进 query', async () => {
    const { report } = await loadReportApi()
    scriptsReq = [{ body: { code: 0, data: { totalDoses: 4, onTimeRate: 0.5 } } }]

    const data = await report.getAdherence({ elderId: 'u-1001', period: 'week' })

    expect(data.totalDoses).toBe(4)
    expect(requests[0].method).toBe('GET')
    expect(requests[0].url).toContain('/medbox/api/v1/adherence')
    expect(requests[0].url).toContain('elderId=u-1001')
    expect(requests[0].url).toContain('period=week')
    expect(requests[0].header.Authorization).toBe('Bearer TOKEN')
    // GET 不注入 X-Request-Id
    expect(requests[0].header['X-Request-Id']).toBeUndefined()
  })

  it('getAdherence：40001（period 非法）兑现 ApiError（BAD_REQUEST）', async () => {
    const { report, CODE } = await loadReportApi()
    scriptsReq = [{ body: { code: 40001, message: 'period 必须为 day / week / month' } }]

    const error = await report.getAdherence({ elderId: 'u-1001', period: 'quarter' }).catch((err) => err)

    expect(error.code).toBe(CODE.BAD_REQUEST)
  })

  it('getAdherence：40302（监护未生效）兑现 ApiError（GUARDIAN_PENDING）', async () => {
    const { report, CODE } = await loadReportApi()
    scriptsReq = [{ body: { code: 40302, message: '监护关系未生效' } }]

    const error = await report.getAdherence({ elderId: 'u-1001', period: 'week' }).catch((err) => err)

    expect(error.code).toBe(CODE.GUARDIAN_PENDING)
  })

  it('exportRecords：走 downloadFile，GET /records/export 带 Bearer，兑现文件信息 + 解析文件名', async () => {
    const { report } = await loadReportApi()
    const filename = 'medication-records-u-1001-2026-01-01-2026-01-31.csv'
    scriptsDl = [
      {
        statusCode: 200,
        tempFilePath: '/tmp/x.csv',
        filePath: '/save/x.csv',
        header: { 'content-disposition': `attachment; filename="${filename}"` },
      },
    ]

    const res = await report.exportRecords({ elderId: 'u-1001' })

    expect(downloads[0].url).toContain('/medbox/api/v1/records/export')
    expect(downloads[0].url).toContain('elderId=u-1001')
    expect(downloads[0].header.Authorization).toBe('Bearer TOKEN')
    expect(res.tempFilePath).toBe('/tmp/x.csv')
    expect(res.filePath).toBe('/save/x.csv')
    expect(res.filename).toBe(filename)
  })

  it('exportRecords：filename*（UTF-8 编码形式）也能解析', async () => {
    const { report } = await loadReportApi()
    scriptsDl = [
      {
        statusCode: 200,
        tempFilePath: '/tmp/x.csv',
        filePath: '/tmp/x.csv',
        header: { 'content-disposition': "attachment; filename*=UTF-8''report.csv" },
      },
    ]

    const res = await report.exportRecords({ elderId: 'u-1001' })

    expect(res.filename).toBe('report.csv')
  })

  it('exportRecords：40301（老人 / 越权导出）兑现 ApiError（FORBIDDEN）', async () => {
    const { report, CODE } = await loadReportApi()
    scriptsDl = [
      {
        statusCode: 403,
        tempFilePath: '/tmp/err',
        filePath: '/tmp/err',
        header: {},
        // 模拟后端全局异常处理器：临时文件内容为 JSON 错误体
        errorBody: JSON.stringify({ code: 40301, message: '无权限执行该操作' }),
      },
    ]

    const error = await report.exportRecords({ elderId: 'u-1001' }).catch((err) => err)

    expect(error.code).toBe(CODE.FORBIDDEN)
  })

  it('exportRecords：40302（监护未生效）兑现 ApiError（GUARDIAN_PENDING）', async () => {
    const { report, CODE } = await loadReportApi()
    scriptsDl = [
      {
        statusCode: 403,
        tempFilePath: '/tmp/err',
        filePath: '/tmp/err',
        header: {},
        errorBody: JSON.stringify({ code: 40302, message: '监护关系未生效' }),
      },
    ]

    const error = await report.exportRecords({ elderId: 'u-1001' }).catch((err) => err)

    expect(error.code).toBe(CODE.GUARDIAN_PENDING)
  })
})

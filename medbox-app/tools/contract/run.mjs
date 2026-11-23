#!/usr/bin/env node
/**
 * 契约场景 runner：把 medbox-spec/contracts/scenarios 下的声明式场景
 * 对 Mock 或真后端执行一遍，用于发现两端契约漂移。
 *
 * 用法：
 *   node tools/contract/run.mjs --profile mock --start-mock
 *   node tools/contract/run.mjs --base http://127.0.0.1:8080/medbox/api/v1 --profile real
 *
 * 参数：
 *   --base <url>      服务端 Base URL（含 context-path，如 .../medbox/api/v1）
 *   --profile <name>  mock | real，决定加载哪份 baseline，默认 mock
 *   --only <keyword>  只跑名称或文件路径包含该关键字的场景
 *   --list            只列场景，不执行
 *   --start-mock      自动拉起 Mock（默认 18099 端口），跑完即关
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(__dirname, '..', '..', '..')
const CONTRACT_DIR = path.join(ROOT, 'medbox-spec', 'contracts')
const SCENARIO_DIRS = ['core', 'mock-only']

function parseArgs(argv) {
  const args = { profile: 'mock', list: false, startMock: false }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--base') args.base = argv[++i]
    else if (a === '--profile') args.profile = argv[++i]
    else if (a === '--only') args.only = argv[++i]
    else if (a === '--list') args.list = true
    else if (a === '--start-mock') args.startMock = true
    else if (a === '--help' || a === '-h') args.help = true
    else throw new Error(`未知参数: ${a}`)
  }
  if (!args.base && args.startMock) args.base = 'http://127.0.0.1:18099/medbox/api/v1'
  return args
}

function usage() {
  console.log(`用法: node tools/contract/run.mjs --profile mock|real --base <url> [--only <关键字>] [--list] [--start-mock]`)
}

function loadBaseline(profile) {
  const file = path.join(CONTRACT_DIR, `baseline.${profile}.json`)
  if (!fs.existsSync(file)) throw new Error(`baseline 不存在: ${file}`)
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function loadScenarios() {
  const scenarios = []
  for (const dir of SCENARIO_DIRS) {
    const full = path.join(CONTRACT_DIR, 'scenarios', dir)
    if (!fs.existsSync(full)) continue
    for (const file of fs.readdirSync(full).filter((f) => f.endsWith('.json')).sort()) {
      const data = JSON.parse(fs.readFileSync(path.join(full, file), 'utf8'))
      scenarios.push({ ...data, __file: path.posix.join(dir, file) })
    }
  }
  return scenarios
}

function lookup(ctx, expr) {
  let cur = ctx
  for (const part of expr.split('.')) {
    if (cur == null) return undefined
    cur = cur[part]
  }
  return cur
}

function resolveString(str, ctx) {
  const exact = str.match(/^\$\{([^}]+)\}$/)
  if (exact) return lookup(ctx, exact[1])
  return str.replace(/\$\{([^}]+)\}/g, (_, expr) => {
    const value = lookup(ctx, expr)
    if (value === undefined) throw new Error(`占位符未解析: \${${expr}}`)
    return String(value)
  })
}

function resolveDeep(value, ctx) {
  if (typeof value === 'string') return resolveString(value, ctx)
  if (Array.isArray(value)) return value.map((item) => resolveDeep(item, ctx))
  if (value && typeof value === 'object') {
    const out = {}
    for (const [key, item] of Object.entries(value)) out[key] = resolveDeep(item, ctx)
    return out
  }
  return value
}

function getPath(value, expr) {
  const parts = expr.replace(/\[(\d+)\]/g, '.$1').split('.').filter(Boolean)
  let cur = value
  for (const part of parts) {
    if (cur == null) return undefined
    cur = cur[part]
  }
  return cur
}

function typeName(value) {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  return typeof value
}

function sameValue(actual, expected) {
  return JSON.stringify(actual) === JSON.stringify(expected)
}

async function request(base, step, ctx) {
  const requestPath = resolveString(step.request.path, ctx)
  const url = base.replace(/\/$/, '') + (requestPath.startsWith('/') ? requestPath : `/${requestPath}`)
  const headers = { Accept: 'application/json' }
  if (step.auth) {
    const token = ctx.vars[step.auth]
    if (!token) throw new Error(`auth 变量不存在: ${step.auth}（是否漏了登录步骤或 capture）`)
    headers.Authorization = `Bearer ${token}`
  }
  let body
  if (step.request.body !== undefined && step.request.body !== null) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(resolveDeep(step.request.body, ctx))
  }
  const response = await fetch(url, { method: step.request.method || 'GET', headers, body })
  const text = await response.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    // 非 JSON 响应留给状态码断言
  }
  return { response, json, text }
}

function assertStep(step, response, json, ctx) {
  const expect = step.expect || {}
  if (expect.status !== undefined && response.status !== expect.status) {
    throw new Error(`HTTP 状态期望 ${expect.status}，实际 ${response.status}`)
  }
  if (expect.code !== undefined && (!json || json.code !== expect.code)) {
    throw new Error(`业务码期望 ${expect.code}，实际 ${json ? json.code : '非 JSON'}`)
  }
  for (const [expr, expectedType] of Object.entries(expect.shape || {})) {
    const actual = getPath(json, expr)
    if (actual === undefined) throw new Error(`字段缺失: ${expr}`)
    if (typeName(actual) !== expectedType) {
      throw new Error(`字段类型: ${expr} 期望 ${expectedType}，实际 ${typeName(actual)}`)
    }
  }
  for (const [expr, rawExpected] of Object.entries(expect.equals || {})) {
    const expected = resolveDeep(rawExpected, ctx)
    const actual = getPath(json, expr)
    if (!sameValue(actual, expected)) {
      throw new Error(`字段值: ${expr} 期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`)
    }
  }
  for (const [expr, min] of Object.entries(expect.minLength || {})) {
    const actual = getPath(json, expr)
    const length = actual == null ? 0 : actual.length
    if (typeof length !== 'number' || length < min) {
      throw new Error(`长度: ${expr} 期望 >= ${min}，实际 ${length}`)
    }
  }
}

async function waitReady(base, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const response = await fetch(base + '/users/me')
      if (response.status) return true
    } catch {
      // 服务未起，继续等
    }
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
  return false
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  if (args.help) {
    usage()
    return
  }
  if (!args.list && !args.base && !args.startMock) throw new Error('必须指定 --base，或使用 --start-mock')

  const baseline = loadBaseline(args.profile)
  let scenarios = loadScenarios().filter((s) => (s.profiles || ['mock', 'real']).includes(args.profile))
  if (args.only) {
    scenarios = scenarios.filter((s) => s.name.includes(args.only) || s.__file.includes(args.only))
  }
  if (args.list) {
    for (const s of scenarios) console.log(`${s.__file}  ${s.name}`)
    return
  }
  if (scenarios.length === 0) throw new Error('没有匹配的场景')

  let mockChild
  if (args.startMock) {
    const port = new URL(args.base).port || '18099'
    mockChild = spawn(process.execPath, [path.join(ROOT, 'medbox-app/tools/mock/src/server.js')], {
      env: { ...process.env, MOCK_PORT: port },
      stdio: 'ignore',
    })
    if (!(await waitReady(args.base))) throw new Error(`Mock 未在 ${args.base} 就绪`)
  }

  const runSuffix = String(Date.now()).slice(-8)
  const runPhone = `139${runSuffix}`
  let scenarioPass = 0
  let scenarioFail = 0
  let stepPass = 0

  try {
    for (const scenario of scenarios) {
      const ctx = { ...baseline, vars: {}, runSuffix, runPhone }
      let failed = false
      console.log(`\n== ${scenario.name} (${scenario.__file})`)
      for (const step of scenario.steps) {
        try {
          const { response, json } = await request(args.base, step, ctx)
          assertStep(step, response, json, ctx)
          for (const [name, expr] of Object.entries(step.capture || {})) {
            const value = getPath(json, expr)
            if (value === undefined) throw new Error(`capture 路径不存在: ${expr}`)
            ctx.vars[name] = value
          }
          stepPass++
          console.log(`  ok   ${step.name}`)
        } catch (error) {
          failed = true
          console.error(`  FAIL ${step.name}: ${error.message}`)
          break
        }
      }
      if (failed) scenarioFail++
      else scenarioPass++
    }
  } finally {
    if (mockChild) mockChild.kill('SIGTERM')
  }

  console.log(`\n结果: ${scenarioPass} 通过 / ${scenarioFail} 失败（场景），${stepPass} 个步骤通过`)
  if (scenarioFail > 0) process.exitCode = 1
}

main().catch((error) => {
  console.error(`[contract] ${error.message}`)
  process.exitCode = 1
})

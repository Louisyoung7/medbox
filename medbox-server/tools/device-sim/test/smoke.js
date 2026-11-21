/**
 * opt-in 端到端冒烟：连接**已启动**的后端 + EMQX + PostgreSQL，逐场景断言真实链路。
 *
 * 前置：SPRING_PROFILES_ACTIVE=local 启动后端并打开 medbox.devtools.enabled=true；
 *      EMQX localhost:1883 可连；抓拍目录可写。CI 默认不跑（无 PG / 无 broker）。
 *
 *   MEDBOX_DEVICE_SIM_SMOKE=true npm run smoke
 *   MEDBOX_SIM_SMOKE_QUICK=true       # 只跑 ack=ok，不等 fail/silent 的重发
 */
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { postCapture } from '../src/camera.js';
import { resolveConfig, validateConfig } from '../src/config.js';

if (process.env.MEDBOX_DEVICE_SIM_SMOKE !== 'true') {
  console.log('[smoke] 未设置 MEDBOX_DEVICE_SIM_SMOKE=true，跳过（CI 无 PG/EMQX）');
  process.exit(0);
}

const here = path.dirname(fileURLToPath(import.meta.url));
const config = validateConfig(resolveConfig({}));
const { baseUrl, deviceId, planId } = config;
const quick = process.env.MEDBOX_SIM_SMOKE_QUICK === 'true';
const ackScenarios = quick ? ['ok'] : ['ok', 'fail', 'silent'];

let passed = 0;
let failed = 0;
const failures = [];

function check(name, condition, detail = '') {
  if (condition) {
    passed += 1;
    console.log(`ok   ${name}`);
  } else {
    failed += 1;
    failures.push(`${name}${detail ? ` — ${detail}` : ''}`);
    console.log(`FAIL ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

async function api(pathname, { method = 'GET', token, body } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${baseUrl}${pathname}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let json = null;
  try {
    json = await response.json();
  } catch {
    json = null;
  }
  return { status: response.status, code: json?.code, data: json?.data, message: json?.message };
}

async function poll(label, probe, timeoutMs = 20_000, intervalMs = 500) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    last = await probe();
    if (last) return last;
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  return null;
}

function startMcu(ack) {
  const child = spawn(process.execPath, [
    path.join(here, '..', 'src', 'cli.js'), 'mcu',
    '--ack', ack,
    '--duration-ms', '90000',
    '--state-dir', config.stateDir,
    '--broker', config.broker,
    '--device-id', deviceId,
    '--product-key', config.productKey,
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  child.stderr.on('data', (chunk) => console.error(`[mcu:${ack}] ${chunk.toString().trim()}`));
  return child;
}

async function stopMcu(child) {
  if (child && !child.killed) child.kill('SIGTERM');
}

async function seed() {
  const result = await api('/_debug/seed?reset=true', { method: 'POST' });
  check('seed 返回 successfully', result.code === 0 && !!result.data?.guardianToken, JSON.stringify(result));
  return result.data ?? {};
}

async function waitOnline(token) {
  return poll('device online', async () => {
    const result = await api(`/devices/${deviceId}/status`, { token });
    return result.data?.onlineState === 'ONLINE' ? result.data : null;
  });
}

async function checkAckScenario(seedData, ack) {
  const token = seedData.guardianToken;
  const child = startMcu(ack);
  try {
    const online = await waitOnline(token);
    check(`[${ack}] 设备上线`, !!online, '确认 EMQX 与后端 MQTT 均已启用');
    if (!online) return;

    const sensors = await api(`/devices/${deviceId}/sensors`, { token });
    const withHeartbeat = (sensors.data?.list ?? sensors.data ?? []).filter((s) => s.lastHeartbeat);
    check(`[${ack}] 传感器心跳落库`, withHeartbeat.length >= 2, JSON.stringify(sensors.data));

    const localHHmm = new Date(Date.now() + 8 * 3600_000).toISOString().slice(11, 16);
    const updated = await api(`/plans/${planId}`, {
      method: 'PUT',
      token,
      body: { name: 'device-sim 冒烟', slots: [{ cellNo: 1, time: localHHmm }], repeatRule: 'DAILY', alarmRule: { miss: true } },
    });
    check(`[${ack}] 计划修改受理`, updated.code === 0, JSON.stringify(updated));

    const expectedSync = ack === 'ok' ? 'SYNCED_TO_DEVICE' : 'SYNC_FAILED';
    const plan = await poll(`plan sync=${expectedSync}`, async () => {
      const result = await api(`/plans/${planId}`, { token });
      return result.data?.syncState === expectedSync ? result.data : null;
    }, 25_000);
    check(`[${ack}] 计划同步状态=${expectedSync}`, !!plan, '检查模拟器是否订阅了 down/# 并按场景回执');

    if (ack === 'ok') {
      const sent = await api(`/devices/${deviceId}/commands`, {
        method: 'POST', token, body: { cmd: 'REBOOT', params: {} },
      });
      check('[ok] 命令受理为 PENDING', sent.code === 0 && sent.data?.state === 'PENDING', JSON.stringify(sent));
      const event = await poll('command SUCCESS', async () => {
        const result = await api(`/devices/${deviceId}/events?page=1&size=20`, { token });
        const rows = result.data?.list ?? [];
        for (const row of rows) {
          if (row.eventType !== 'COMMAND' || !row.detail) continue;
          try {
            const detail = JSON.parse(row.detail);
            if (detail.commandId === sent.data?.commandId && detail.state === 'SUCCESS') return detail;
          } catch { /* detail 不是 JSON，跳过 */ }
        }
        return null;
      });
      check('[ok] 命令回执落 COMMAND SUCCESS 事件', !!event, JSON.stringify(event));
    }
  } finally {
    await stopMcu(child);
  }
}

async function checkCamera(token) {
  const capturedAt = new Date().toISOString();
  const taken = await postCapture(validateConfig(resolveConfig({
    capture: 'taken', cellNo: 1, planId, capturedAt,
  })));
  check('摄像头 TAKEN 上报成功', taken.code === 0 && !!taken.captureId, JSON.stringify(taken));

  const untaken = await postCapture(validateConfig(resolveConfig({
    capture: 'untaken', cellNo: 2, planId, capturedAt,
  })));
  check('摄像头 UNTAKEN 上报成功', untaken.code === 0 && !!untaken.captureId, JSON.stringify(untaken));

  const from = new Date(Date.now() - 5 * 60_000).toISOString();
  const to = new Date(Date.now() + 5 * 60_000).toISOString();
  const captures = await api(`/captures?elderId=e-1001&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&page=1&size=50`, { token });
  const list = captures.data?.list ?? [];
  const untakenRow = list.find((row) => row.captureId === untaken.captureId);
  check('UNTAKEN 留证图已落库（有 mime/sizeBytes）',
    !!untakenRow && untakenRow.takenResult === 'UNTAKEN' && !!untakenRow.mime && untakenRow.sizeBytes > 0,
    JSON.stringify(untakenRow));
  const takenRow = list.find((row) => row.captureId === taken.captureId);
  check('TAKEN 不附图', !!takenRow && takenRow.takenResult === 'TAKEN' && !takenRow.mime, JSON.stringify(takenRow));

  const mismatch = await postCapture(validateConfig(resolveConfig({ capture: 'taken', deviceIdMismatch: true })));
  check('X-Device-Id 不一致 → 40001', mismatch.code === 40001, JSON.stringify(mismatch));
  const unknown = await postCapture(validateConfig(resolveConfig({ capture: 'taken', unknownDevice: true })));
  check('未登记摄像头 → 40301', unknown.code === 40301, JSON.stringify(unknown));

  const records = await api(`/records?elderId=e-1001&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&page=1&size=50`, { token });
  const recordRows = records.data?.list ?? [];
  check('抓拍生成服药记录', recordRows.some((row) => row.captureId === taken.captureId), JSON.stringify(records.data));
}

try {
  console.log('== 端到端冒烟（真后端 + EMQX）==');
  const seedData = await seed();
  if (seedData.guardianToken) {
    for (const ack of ackScenarios) {
      await checkAckScenario(seedData, ack);
      if (ack !== ackScenarios.at(-1)) await seed();
    }
    await checkCamera(seedData.guardianToken);
  }
  console.log('== MISS / WS 业务帧：需定时扫描与 WS debug 端点，本脚本未覆盖（见 README）==');
} catch (error) {
  failed += 1;
  failures.push(`脚本异常：${error.message}`);
  console.error(error);
} finally {
  console.log(`结果: ${passed} 通过 / ${failed} 失败`);
  if (failures.length) console.log(failures.map((item) => `  - ${item}`).join('\n'));
  process.exit(failed ? 1 : 0);
}

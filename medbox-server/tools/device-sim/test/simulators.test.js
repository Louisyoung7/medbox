import assert from 'node:assert/strict';
import http from 'node:http';
import test from 'node:test';

import { postCapture } from '../src/camera.js';
import { resolveConfig, validateConfig } from '../src/config.js';
import { runMcu } from '../src/mcu.js';

function configFor(overrides) {
  return validateConfig(resolveConfig(overrides));
}

test('MCU --offline 不连 broker，按 durationMs 返回', async () => {
  const result = await runMcu(configFor({ offline: true, durationMs: 20 }));
  assert.equal(result.offline, true);
});

async function captureWithServer(overrides) {
  const seen = { request: null };
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      seen.request = {
        url: req.url,
        body: Buffer.concat(chunks).toString('utf8'),
      };
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ code: 0, data: { captureId: 'cap-sim-1' } }));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  try {
    const config = configFor({ ...overrides, baseUrl: `http://127.0.0.1:${port}/medbox/api/v1` });
    const result = await postCapture(config);
    return { result, seen };
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

test('摄像头 TAKEN 上报：字段齐全且不附图', async () => {
  const { result, seen } = await captureWithServer({ capture: 'taken', cellNo: 1, planId: 'p-3301' });
  assert.equal(result.captureId, 'cap-sim-1');
  assert.equal(result.takenResult, 'TAKEN');
  assert.match(seen.request.url, /\/devices\/BOXA-DEBUG-01\/captures$/);
  assert.match(seen.request.body, /name="cellNo"/);
  assert.match(seen.request.body, /\r\n1\r\n/);
  assert.match(seen.request.body, /name="takenResult"\r\n\r\nTAKEN/);
  assert.doesNotMatch(seen.request.body, /name="file"/);
});

test('摄像头 UNTAKEN 上报必须附图', async () => {
  const { result, seen } = await captureWithServer({ capture: 'untaken' });
  assert.equal(result.takenResult, 'UNTAKEN');
  assert.match(seen.request.body, /name="takenResult"\r\n\r\nUNTAKEN/);
  assert.match(seen.request.body, /name="file"; filename="capture.jpg"/);
});

test('摄像头 --capture none 不上报', async () => {
  const { result, seen } = await captureWithServer({ capture: 'none' });
  assert.equal(result.skipped, true);
  assert.equal(seen.request, null);
});

test('错误路径开关：未登记设备 → 40301', async () => {
  const unknown = await captureWithServer({ capture: 'taken', unknownDevice: true });
  assert.match(unknown.seen.request.url, /\/devices\/UNREGISTERED-DEVICE\/captures$/);
});

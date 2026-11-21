import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { resolveConfig, validateConfig } from '../src/config.js';
import { sampleImage } from '../src/camera.js';
import { formatMsgId, nextMsgId, stateFile } from '../src/msg-id.js';
import {
  ackUp,
  buildTopic,
  errorEvent,
  luxTelemetry,
  parseDownlink,
  sensorHeartbeat,
  telemetry,
} from '../src/payloads.js';
import { parseArgs } from '../src/cli.js';

test('msgId 固定为 deviceId-10位递增序号', () => {
  assert.equal(formatMsgId('BOXA-DEBUG-01', 124), 'BOXA-DEBUG-01-0000000124');
  assert.equal(formatMsgId('BOXA-DEBUG-01', 0), 'BOXA-DEBUG-01-0000000000');
  assert.throws(() => formatMsgId('BOXA-DEBUG-01', -1), TypeError);
});

test('msgId 序号持久化：新建实例继续递增，不归零', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'medbox-sim-'));
  const first = nextMsgId('BOXA-DEBUG-01', dir);
  const second = nextMsgId('BOXA-DEBUG-01', dir);
  const third = nextMsgId('BOXA-DEBUG-01', dir);
  assert.equal(first, 'BOXA-DEBUG-01-0000000001');
  assert.equal(second, 'BOXA-DEBUG-01-0000000002');
  assert.equal(third, 'BOXA-DEBUG-01-0000000003');
  assert.equal(JSON.parse(fs.readFileSync(stateFile('BOXA-DEBUG-01', dir), 'utf8')).seq, 3);
});

test('topic 按 medbox/{productKey}/{deviceId}/... 构造', () => {
  assert.equal(
    buildTopic('medbox', 'medbox-box', 'BOXA-DEBUG-01', 'up/telemetry'),
    'medbox/medbox-box/BOXA-DEBUG-01/up/telemetry',
  );
});

test('上行报文字段与 spec 03 一致', () => {
  const base = { msgId: 'BOXA-DEBUG-01-0000000001', ts: 1759474487000 };
  assert.deepEqual(JSON.parse(telemetry({ ...base, sensorId: 'BOXA-DEBUG-01-S-TH01', temperature: 24.6, humidity: 52.1 })), {
    ...base, sensorId: 'BOXA-DEBUG-01-S-TH01', temperature: 24.6, humidity: 52.1,
  });
  assert.deepEqual(JSON.parse(luxTelemetry({ ...base, sensorId: 'BOXA-DEBUG-01-S-LX01', lux: 120 })), {
    ...base, sensorId: 'BOXA-DEBUG-01-S-LX01', lux: 120,
  });
  assert.deepEqual(JSON.parse(sensorHeartbeat({ ...base, sensors: [{ sensorId: 'S1', online: true }] })), {
    ...base, sensors: [{ sensorId: 'S1', online: true }],
  });
  assert.deepEqual(JSON.parse(errorEvent({ ...base, type: 'DOOR_TIMEOUT', detail: 'timeout' })), {
    ...base, type: 'DOOR_TIMEOUT', detail: 'timeout',
  });
  assert.throws(() => errorEvent({ ...base, type: 'UNKNOWN', detail: 'x' }), TypeError);
});

test('上行 ack 带 ackFor / result / reason，拒绝非法 result', () => {
  const parsed = JSON.parse(ackUp({ msgId: 'm-1', ackFor: 'd-001', result: 'FAILED', reason: 'mock_failure' }));
  assert.equal(parsed.ackFor, 'd-001');
  assert.equal(parsed.result, 'FAILED');
  assert.equal(parsed.reason, 'mock_failure');
  assert.throws(() => ackUp({ msgId: 'm-1', ackFor: 'd-001', result: 'MAYBE' }), TypeError);
});

test('下行解析：合法 JSON 取 needAck / msgId，坏输入返回 null', () => {
  assert.equal(parseDownlink('{"msgId":"d-001","needAck":true}').needAck, true);
  assert.equal(parseDownlink('not-json'), null);
  assert.equal(parseDownlink('42'), null);
});

test('参数解析与校验：短横线转驼峰，场景值只允许枚举', () => {
  assert.deepEqual(parseArgs(['mcu', '--ack', 'silent', '--cell-no', '2', '--offline']), {
    command: 'mcu',
    flags: { ack: 'silent', cellNo: '2', offline: true },
  });
  assert.throws(() => parseArgs(['bad']), TypeError);
  assert.throws(() => validateConfig(resolveConfig({ ack: 'ignore' })), TypeError);
  assert.throws(() => validateConfig(resolveConfig({ capture: 'maybe' })), TypeError);
  assert.throws(() => validateConfig(resolveConfig({ cellNo: 4 })), TypeError);
  assert.equal(validateConfig(resolveConfig({ ack: 'fail' })).ack, 'fail');
});

test('UNTAKEN 留证图是 image/jpeg 且远小于 10MB', () => {
  const image = sampleImage();
  assert.equal(image.type, 'image/jpeg');
  assert.ok(image.size > 0 && image.size < 10 * 1024 * 1024);
});

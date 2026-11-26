import mqtt from 'mqtt';
import { nextMsgId } from './msg-id.js';
import {
  ackUp,
  buildTopic,
  errorEvent,
  luxTelemetry,
  parseDownlink,
  telemetry,
} from './payloads.js';

function log(topic, msgId, scenario) {
  console.log(JSON.stringify({ topic, msgId, scenario }));
}

function waitForStop(durationMs, signal) {
  return new Promise((resolve) => {
    let timer;
    const done = () => {
      if (timer) clearTimeout(timer);
      signal?.removeEventListener?.('abort', done);
      process.removeListener('SIGINT', done);
      resolve();
    };
    if (durationMs > 0) timer = setTimeout(done, durationMs);
    if (signal) {
      if (signal.aborted) done();
      else signal.addEventListener('abort', done, { once: true });
    } else {
      process.once('SIGINT', done);
    }
  });
}

/**
 * 模拟药箱主控：唯一建立 MQTT 连接的设备。
 *
 * 严格按 spec 03：clientId = deviceId、retained 上线 + LWT、订阅 down/#、按场景回 up/ack；
 * 温湿与光照各发一条遥测（主控直接上报，无 sensorId）；不再有传感器心跳。--offline 时完全不连 broker。
 */
export async function runMcu(config, { signal } = {}) {
  const { deviceId, productKey, topicPrefix } = config;
  const up = (suffix) => buildTopic(topicPrefix, productKey, deviceId, `up/${suffix}`);
  const down = buildTopic(topicPrefix, productKey, deviceId, 'down/#');
  const statusTopic = buildTopic(topicPrefix, productKey, deviceId, 'status');
  const scenario = `mcu:${config.ack}${config.offline ? ':offline' : ''}`;

  if (config.offline) {
    log(statusTopic, '-', `${scenario}:not-connected`);
    await waitForStop(config.durationMs, signal);
    return { offline: true };
  }

  const client = mqtt.connect(config.broker, {
    clientId: deviceId,
    keepalive: config.keepaliveSeconds,
    clean: false,
    reconnectPeriod: 3_000,
    will: { topic: statusTopic, payload: '{"online":false}', qos: 0, retain: true },
  });

  const connected = new Promise((resolve, reject) => {
    client.once('connect', resolve);
    client.once('error', reject);
  });
  await connected;
  client.removeAllListeners('error');
  client.on('error', (error) => console.error(`[mcu] MQTT error: ${error.message}`));

  client.publish(statusTopic, '{"online":true}', { qos: 0, retain: true });
  client.subscribe(down, { qos: 1 });

  const publishUp = (topic, payload) => {
    const msgId = JSON.parse(payload).msgId;
    client.publish(topic, payload, { qos: topic.endsWith('telemetry') ? 0 : 1 });
    log(topic, msgId, scenario);
  };

  const emitTelemetry = () => {
    publishUp(up('telemetry'), telemetry({
      msgId: nextMsgId(deviceId, config.stateDir),
      temperature: 24.6,
      humidity: 52.1,
    }));
    publishUp(up('telemetry'), luxTelemetry({
      msgId: nextMsgId(deviceId, config.stateDir),
      lux: 120,
    }));
  };

  client.on('message', (topic, payload) => {
    const downlink = parseDownlink(payload);
    if (!downlink || downlink.needAck !== true || config.ack === 'silent') return;
    const result = config.ack === 'fail' ? 'FAILED' : 'OK';
    const reason = config.ack === 'fail' ? 'mock_failure' : null;
    const ackTopic = up('ack');
    const body = ackUp({
      msgId: nextMsgId(deviceId, config.stateDir),
      ackFor: downlink.msgId,
      result,
      reason,
    });
    client.publish(ackTopic, body, { qos: 1 });
    log(ackTopic, JSON.parse(body).msgId, `${scenario}:ackFor=${downlink.msgId}`);
  });

  emitTelemetry();
  if (config.errorType) {
    publishUp(up('event/error'), errorEvent({
      msgId: nextMsgId(deviceId, config.stateDir),
      type: config.errorType,
      detail: 'device-sim 模拟异常',
    }));
  }

  const telemetryTimer = setInterval(emitTelemetry, config.telemetryIntervalMs);
  await waitForStop(config.durationMs, signal);
  clearInterval(telemetryTimer);
  client.end(true);
  return { offline: false };
}

/** 上行报文构造器，字段口径严格按 medbox-spec/03 第 2/3 章，不做协议扩展。 */

export function buildTopic(prefix, productKey, deviceId, suffix) {
  return [prefix, productKey, deviceId, suffix].join('/');
}

/** 温湿度遥测：主控直接上报，不带 sensorId。 */
export function telemetry({ msgId, ts = Date.now(), temperature, humidity }) {
  return JSON.stringify({ msgId, ts, temperature, humidity });
}

/** 光照遥测：主控直接上报，不带 sensorId。 */
export function luxTelemetry({ msgId, ts = Date.now(), lux }) {
  return JSON.stringify({ msgId, ts, lux });
}

export function errorEvent({ msgId, ts = Date.now(), type, detail }) {
  if (!['DOOR_TIMEOUT', 'CAMERA_FAILED', 'HW_FAULT'].includes(type)) {
    throw new TypeError(`设备异常 type 非法：${type}`);
  }
  return JSON.stringify({ msgId, ts, type, detail });
}

export function ackUp({ msgId, ts = Date.now(), ackFor, result, reason = null }) {
  if (!['OK', 'FAILED'].includes(result)) {
    throw new TypeError(`ack result 非法：${result}`);
  }
  return JSON.stringify({ msgId, ts, ackFor, result, reason });
}

/** 从服务端下行的 schedule/command 载荷中提取 needAck 与 msgId；非法 JSON 返回 null。 */
export function parseDownlink(payload) {
  try {
    const parsed = JSON.parse(payload.toString('utf8'));
    return typeof parsed === 'object' && parsed !== null ? parsed : null;
  } catch {
    return null;
  }
}

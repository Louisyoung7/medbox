import path from 'node:path';

const env = (name, fallback) => (process.env[name] ?? fallback);

/**
 * 模拟器配置：默认值对齐后端 devtools 种子（BOXA-DEBUG-01 / medbox-box / p-3301），
 * 所有项都可由命令行覆盖，便于联调非种子设备。
 *
 * 系统只建模药箱主控一种设备：不再有传感器 / 摄像头子设备 ID 与子设备心跳。
 */
export function resolveConfig(overrides = {}) {
  const deviceId = overrides.deviceId ?? env('MEDBOX_SIM_DEVICE_ID', 'BOXA-DEBUG-01');
  const productKey = overrides.productKey ?? env('MEDBOX_SIM_PRODUCT_KEY', 'medbox-box');
  const stateDir = overrides.stateDir ?? env('MEDBOX_SIM_STATE_DIR', '.state');

  return {
    broker: overrides.broker ?? env('MEDBOX_SIM_BROKER', 'tcp://localhost:1883'),
    baseUrl: overrides.baseUrl ?? env('MEDBOX_SIM_BASE_URL', 'http://localhost:8080/medbox/api/v1'),
    deviceId,
    productKey,
    topicPrefix: overrides.topicPrefix ?? env('MEDBOX_SIM_TOPIC_PREFIX', 'medbox'),
    planId: overrides.planId ?? env('MEDBOX_SIM_PLAN_ID', 'p-3301'),
    stateDir: path.resolve(stateDir),
    keepaliveSeconds: Number(overrides.keepaliveSeconds ?? env('MEDBOX_SIM_KEEPALIVE', 60)),
    telemetryIntervalMs: Number(overrides.telemetryIntervalMs ?? env('MEDBOX_SIM_TELEMETRY_INTERVAL_MS', 60_000)),
    ack: overrides.ack ?? 'ok',
    capture: overrides.capture ?? 'taken',
    cellNo: Number(overrides.cellNo ?? 1),
    offline: Boolean(overrides.offline ?? false),
    capturedAt: overrides.capturedAt ?? null,
    reason: overrides.reason ?? 'NORMAL',
    errorType: overrides.errorType ?? null,
    unknownDevice: Boolean(overrides.unknownDevice ?? false),
    durationMs: Number(overrides.durationMs ?? 0),
    timeoutMs: Number(overrides.timeoutMs ?? 10_000),
  };
}

export function validateConfig(config) {
  if (!/^[A-Za-z0-9_-]{4,64}$/.test(config.deviceId)) {
    throw new TypeError(`deviceId 不合法：${config.deviceId}`);
  }
  if (!['ok', 'fail', 'silent'].includes(config.ack)) {
    throw new TypeError(`--ack 只接受 ok|fail|silent：${config.ack}`);
  }
  if (!['taken', 'untaken', 'none'].includes(config.capture)) {
    throw new TypeError(`--capture 只接受 taken|untaken|none：${config.capture}`);
  }
  if (![1, 2, 3].includes(config.cellNo)) {
    throw new TypeError(`cellNo 只能是 1/2/3：${config.cellNo}`);
  }
  if (!['NORMAL', 'MANUAL'].includes(config.reason)) {
    throw new TypeError(`reason 只接受 NORMAL|MANUAL：${config.reason}`);
  }
  if (config.capturedAt !== null && Number.isNaN(Date.parse(config.capturedAt))) {
    throw new TypeError(`capturedAt 必须是 ISO-8601 时刻：${config.capturedAt}`);
  }
  return config;
}

/** 极小的 1x1 JPEG，仅用于 UNTAKEN 留证；不入库、不写二进制文件。 */
const SAMPLE_JPEG_BASE64 =
  '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a' +
  'HBwcJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPDs0NDT/wAALCAABAAEBAREA/8QAFAABAQAAAAAAAAAAAAAAAAAAAAv' +
  '/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8AmAA//9k=';

export function sampleImage() {
  return new Blob([Buffer.from(SAMPLE_JPEG_BASE64, 'base64')], { type: 'image/jpeg' });
}

function buildForm(config) {
  const form = new FormData();
  form.append('reason', config.reason);
  if (config.planId) form.append('planId', config.planId);
  if (config.planId) form.append('cellNo', String(config.cellNo));
  form.append('takenResult', config.capture === 'untaken' ? 'UNTAKEN' : 'TAKEN');
  if (config.capturedAt) form.append('capturedAt', config.capturedAt);
  if (config.capture === 'untaken') form.append('file', sampleImage(), 'capture.jpg');
  return form;
}

/**
 * 模拟摄像头经 HTTP multipart 直报后端的服药事件（spec 03 第 4 章 / 02b §1.1）。
 *
 * <p>路径用主控 deviceId，不再有摄像头子设备 ID 与 X-Device-Id 头。
 * TAKEN 不附图；UNTAKEN 必附图；--capture none 表示到点不上报（供后端判 MISS）。
 * --unknown-device 验证未登记设备 → 40301。
 */
export async function postCapture(config) {
  if (config.capture === 'none') {
    console.log(JSON.stringify({ scenario: 'camera:none', action: 'skipped' }));
    return { skipped: true };
  }

  const deviceId = config.unknownDevice ? 'UNREGISTERED-DEVICE' : config.deviceId;
  const url = `${config.baseUrl}/devices/${encodeURIComponent(deviceId)}/captures`;
  const response = await fetch(url, {
    method: 'POST',
    body: buildForm(config),
  });
  const body = await response.json();
  const result = {
    status: response.status,
    code: body?.code,
    captureId: body?.data?.captureId ?? null,
    deviceId,
    takenResult: config.capture === 'untaken' ? 'UNTAKEN' : 'TAKEN',
  };
  console.log(JSON.stringify({
    topic: url,
    msgId: '-',
    scenario: `camera:${config.capture}`,
    ...result,
  }));
  return result;
}

export async function runCamera(config, { signal } = {}) {
  const result = await postCapture(config);
  if (config.durationMs > 0) {
    await new Promise((resolve) => {
      const timer = setTimeout(resolve, config.durationMs);
      signal?.addEventListener?.('abort', () => { clearTimeout(timer); resolve(); }, { once: true });
    });
  }
  return result;
}

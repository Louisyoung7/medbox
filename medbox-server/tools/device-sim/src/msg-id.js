import fs from 'node:fs';
import path from 'node:path';

const SEQ_MODULO = 10_000_000_000;

/** msgId 固定形态：{deviceId}-{10位递增序号}（见 spec 03 第 1 章）。 */
export function formatMsgId(deviceId, seq) {
  if (!Number.isSafeInteger(seq) || seq < 0) {
    throw new TypeError('msgId 序号必须是非负安全整数');
  }
  return `${deviceId}-${String(seq % SEQ_MODULO).padStart(10, '0')}`;
}

export function stateFile(deviceId, stateDir) {
  return path.join(stateDir, `${deviceId}.json`);
}

function readSeq(file) {
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    return Number.isSafeInteger(parsed.seq) && parsed.seq >= 0 ? parsed.seq : 0;
  } catch (error) {
    if (error.code === 'ENOENT') return 0;
    throw new Error(`读取 msgId 状态失败 ${file}：${error.message}`);
  }
}

/**
 * 取下一个 msgId 并把序号原子写回 `.state/{deviceId}.json`，保证进程重启后不归零。
 * 序号先自增再写盘；写盘失败时抛错，不发可能命中服务端去重的旧序号。
 */
export function nextMsgId(deviceId, stateDir, now = () => Date.now()) {
  const file = stateFile(deviceId, stateDir);
  fs.mkdirSync(stateDir, { recursive: true });
  const seq = readSeq(file) + 1;
  const tmp = `${file}.${process.pid}.${now()}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify({ seq, updatedAt: new Date(now()).toISOString() }), 'utf8');
  fs.renameSync(tmp, file);
  return formatMsgId(deviceId, seq);
}

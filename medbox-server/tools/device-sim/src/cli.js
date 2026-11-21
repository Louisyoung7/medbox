import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { resolveConfig, validateConfig } from './config.js';
import { runCamera } from './camera.js';
import { runMcu } from './mcu.js';

const camel = (key) => key.replace(/-([a-z])/g, (_, char) => char.toUpperCase());

export function parseArgs(argv) {
  const [command, ...rest] = argv;
  if (!['mcu', 'camera'].includes(command)) {
    throw new TypeError('用法：node src/cli.js <mcu|camera> [--flag value ...]');
  }
  const flags = {};
  for (let i = 0; i < rest.length; i += 1) {
    const token = rest[i];
    if (!token.startsWith('--')) throw new TypeError(`参数必须以 -- 开头：${token}`);
    const key = camel(token.slice(2));
    const next = rest[i + 1];
    if (next === undefined || next.startsWith('--')) {
      flags[key] = true;
    } else {
      flags[key] = next;
      i += 1;
    }
  }
  return { command, flags };
}

const invokedAsMain = process.argv[1]
  && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (invokedAsMain) {
  try {
    const { command, flags } = parseArgs(process.argv.slice(2));
    const config = validateConfig(resolveConfig(flags));
    const result = command === 'mcu' ? await runMcu(config) : await runCamera(config);
    console.log(JSON.stringify({ done: true, command, result }));
  } catch (error) {
    console.error(`[device-sim] ${error.message}`);
    process.exitCode = 1;
  }
}

// Vitest 配置（feat/mp-ui-kit）：仅测纯逻辑工具 datetime.js / permission.js
// 固定时区保证时间格式化单测确定性（Node 懒读 TZ，配置加载时设置即可生效）
process.env.TZ = 'Asia/Shanghai'

import { defineConfig } from 'vitest/config'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['src/utils/__tests__/**/*.test.js'],
  },
})

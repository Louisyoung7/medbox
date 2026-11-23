// Vitest 配置（feat/mp-ui-kit 建，feat/mp-ws 改）：测纯逻辑（utils / store，不编译 .vue）
// 固定时区保证时间格式化单测确定性（Node 懒读 TZ，配置加载时设置即可生效）
process.env.TZ = 'Asia/Shanghai'

import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

/**
 * Vitest 配置：**独立于 `vite.config.js`**。
 *
 * <p>`feat/mp-ws` 的改动只有一处 —— `test.include` 从 `src/utils/__tests__` 放开到
 * `src/**\/__tests__`（`store/` 也要写测试）。为什么改在本文件而不是 `vite.config.js`：
 * `@dcloudio/vite-plugin-uni` 会往构建配置里塞一套 `test.include`（只收 utils），
 * 而构建配置里**插件的返回值优先级高于用户配置**，改了不生效；vitest 优先读本文件。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/__tests__/**/*.test.js'],
  },
})

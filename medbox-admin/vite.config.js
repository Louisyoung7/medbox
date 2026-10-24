import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期把 /medbox 代理到后端 8080，规避浏览器同源 / CORS 预检（见 medbox-spec/01 第 4 章）。
// 后端地址取 .env 的 VITE_SERVER_HOST（默认 127.0.0.1）。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const host = env.VITE_SERVER_HOST || '127.0.0.1'
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        '/medbox': {
          target: `http://${host}:8080`,
          changeOrigin: true,
        },
      },
    },
  }
})

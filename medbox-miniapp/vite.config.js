import { defineConfig, loadEnv } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  // 反代目标与编译期同一个 HOST（.env.local 的 VITE_SERVER_HOST），换后端 IP 只需改一处。
  // 浏览器联调时看不到跨域：REST 全部走下面的 proxy。小程序 / 真机不走 dev server，不受影响。
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const target = `http://${env.VITE_SERVER_HOST || '127.0.0.1'}:8080`

  return {
    plugins: [uni()],
    server: {
      // 只影响 dev server（build 不走这里），小程序端产物不受影响
      proxy: {
        '/medbox': { target, changeOrigin: true, ws: true },
      },
    },
  }
})

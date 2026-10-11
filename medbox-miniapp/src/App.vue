<script>
import { setupAuth } from '@/store/auth.js'
import { connect } from '@/store/ws.js'

export default {
  onLaunch: function () {
    // feat/mp-auth：注入 40101 接管 → 装路由守卫 → 静默校验本地登录态
    // （access token 已过期但 refresh 仍有效时，这里会顺带完成静默续期）
    setupAuth().then((loggedIn) => {
      // feat/mp-ws：已登录才起长连接（老人端默认订阅自己）；未登录由登录成功后的 onShow 补连
      if (loggedIn) {
        connect()
      }
    })
  },
  onShow: function () {
    // 切后台时系统会掐断长连接，回前台幂等补连（已连接则直接返回）
    connect()
  },
  onHide: function () {
    // 不主动断开：断开后后端补推窗口（10 分钟）照样推进，回前台补连还能补到事件
  },
}
</script>

<style>
/*每个页面公共css */
</style>

<script>
import { setupAuth } from '@/store/auth.js'

export default {
  onLaunch: function () {
    // feat/mp-auth：注入 40101 接管 → 装路由守卫 → 静默校验本地登录态
    // （access token 已过期但 refresh 仍有效时，这里会顺带完成静默续期）
    setupAuth()
  },
  onShow: function () {
    console.log('App Show')
  },
  onHide: function () {
    console.log('App Hide')
  },
}
</script>

<style>
/*每个页面公共css */
</style>

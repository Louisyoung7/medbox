<script>
import { setupAuth } from '@/store/auth.js'
import { connect } from '@/store/ws.js'
import {
  loadVisibleElderIds,
  refreshUnreadCount,
  getAlarmState,
} from '@/store/alarm.js'
import AlarmRealtime from '@/components/AlarmRealtime.vue'

export default {
  components: { AlarmRealtime },
  onLaunch: function () {
    // feat/mp-auth：注入 40101 接管 → 装路由守卫 → 静默校验本地登录态
    // （access token 已过期但 refresh 仍有效时，这里会顺带完成静默续期）
    setupAuth().then((loggedIn) => {
      // feat/mp-ws：已登录才起长连接；监护人需显式传可见老人（由设备列表推导）
      if (loggedIn) this.bootstrapRealtime()
    })
  },
  onShow: function () {
    // 切后台系统会掐断长连接，回前台幂等补连；监护人传已知 elderIds，老人端由 ws 默认自己
    connect({ elderIds: getAlarmState().elderIds })
    refreshUnreadCount()
  },
  onHide: function () {
    // 不主动断开：断开后后端补推窗口（10 分钟）照样推进，回前台补连还能补到事件
  },
  methods: {
    async bootstrapRealtime() {
      await loadVisibleElderIds()
      connect({ elderIds: getAlarmState().elderIds })
      refreshUnreadCount()
    },
  },
}
</script>

<template>
  <AlarmRealtime />
</template>

<style>
/*每个页面公共css */
</style>

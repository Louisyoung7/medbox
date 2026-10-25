<template>
  <view class="page">
    <MbLoading v-if="loading" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <template v-else-if="device">
      <MbCard title="设备档案">
        <MbCell title="设备编号" :value="device.deviceId" />
        <MbCell title="设备类型" :value="deviceTypeLabel(device.deviceType)" />
        <MbCell title="产品 Key" :value="device.productKey || '-'" />
        <MbCell title="时区" :value="device.timezone || '-'" />
        <MbCell title="登记时间" :value="formatDateTime(device.createdAt)" />
      </MbCard>

      <MbCard title="药箱库存">
        <MbCell
          title="库存清单"
          label="数量 / 生产日期 / 有效期"
          clickable
          arrow
          @click="goStock"
        />
      </MbCard>

      <MbCard title="在线状态">
        <MbCell title="当前状态">
          <template #extra>
            <text class="state" :class="device.onlineState === 'ONLINE' ? 'state--on' : 'state--off'">
              {{ device.onlineState === 'ONLINE' ? '在线' : '离线' }}
            </text>
          </template>
        </MbCell>
        <MbCell
          title="最后心跳"
          :value="device.lastHeartbeat ? formatRelative(device.lastHeartbeat) : '从未上线'"
        />
      </MbCard>

      <MbCard title="传感器子设备">
        <template v-if="sensors.length">
          <MbCell
            v-for="sensor in sensors"
            :key="sensor.deviceId"
            :title="sensor.deviceId"
            :label="deviceTypeLabel(sensor.deviceType)"
          >
            <template #extra>
              <text class="state" :class="sensor.onlineState === 'ONLINE' ? 'state--on' : 'state--off'">
                {{ sensor.onlineState === 'ONLINE' ? '在线' : '离线' }}
              </text>
            </template>
          </MbCell>
        </template>
        <MbEmpty v-else text="暂无传感器" />
      </MbCard>

      <RoleGate entry="deviceCommand">
        <MbCard title="远程控制">
          <view class="cmd-grid">
            <view v-for="cmd in CMD_LIST" :key="cmd" class="cmd-item">
              <MbButton
                type="primary"
                size="small"
                :loading="cmdSending === cmd"
                @click="onCommand(cmd)"
              >{{ cmdLabel(cmd) }}</MbButton>
              <text v-if="commandStateOf(cmd)" class="cmd-state" :class="commandStateClass(cmd)">{{ commandStateOf(cmd) }}</text>
            </view>
          </view>
          <text v-if="commandHint" class="cmd-hint">{{ commandHint }}</text>
        </MbCard>
      </RoleGate>

      <MbCard title="设备事件">
        <view v-if="recentEvents.length" class="event-list">
          <view v-for="ev in recentEvents" :key="ev.eventId" class="event-row">
            <text class="event-type" :class="eventClass(ev)">{{ ev.eventTypeLabel }}</text>
            <text class="event-detail">{{ eventDetailText(ev) }}</text>
            <text class="event-time">{{ formatRelative(ev.createdAt) }}</text>
          </view>
        </view>
        <MbEmpty v-else text="暂无事件" />
        <view class="event-more" @click="goEvents">
          <text class="event-more__text">查看全部 ›</text>
        </view>
      </MbCard>
    </template>
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbLoading from '@/components/MbLoading.vue'
import MbButton from '@/components/MbButton.vue'
import RoleGate from '@/components/RoleGate.vue'
import { getDevice, getDeviceEvents, sendDeviceCommand } from '@/api/device.js'
import { CODE } from '@/api/request.js'
import { onEvent, onResync, WS_EVENT } from '@/store/ws.js'
import { deviceTypeLabel } from '@/utils/ws-frame.js'
import { applyStatus } from '@/utils/device-status.js'
import { CMD_LIST, cmdLabel, commandStateLabel, parseEventsPage, toEventView } from '@/utils/device.js'
import { formatDateTime, formatRelative } from '@/utils/datetime.js'
import { getUserInfo } from '@/store/user.js'

export default {
  name: 'DeviceDetailPage',
  components: { MbCard, MbCell, MbEmpty, MbLoading, MbButton, RoleGate },
  data() {
    return {
      device: null,
      deviceId: '',
      loading: false,
      error: '',
      offStatus: null,
      offResync: null,
      // 远程控制：当前正在下发的命令（loading 态）、错误提示、已下发命令（用于回显 PENDING→成功/失败）
      cmdSending: '',
      commandHint: '',
      issuedCommands: [],
      // 设备事件：详情页展示最近几条
      recentEvents: [],
    }
  },
  computed: {
    sensors() {
      return (this.device && this.device.sensors) || []
    },
    // 命令枚举（蜂鸣/解锁/校准/重启），供卡片渲染
    CMD_LIST() {
      return CMD_LIST
    },
  },
  onLoad(options) {
    this.deviceId = (options && options.deviceId) || ''
    this.offStatus = onEvent(WS_EVENT.DEVICE_STATUS, this.onDeviceStatus)
    this.offResync = onResync(this.handleResync)
    this.load()
    this.loadEvents()
  },
  onUnload() {
    if (this.offStatus) this.offStatus()
    if (this.offResync) this.offResync()
  },
  methods: {
    deviceTypeLabel,
    formatDateTime,
    formatRelative,
    cmdLabel,
    commandStateLabel,
    async load() {
      if (!this.deviceId) {
        this.error = '缺少设备编号'
        return
      }
      this.loading = true
      this.error = ''
      try {
        this.device = await getDevice(this.deviceId)
      } catch (err) {
        // 不存在 / 他人设备 / 传了子设备编号，后端一律 40301（不返回 40401）
        this.error = err && err.code === CODE.FORBIDDEN ? '无权访问或设备不存在' : (err && err.message) || '加载失败'
      } finally {
        this.loading = false
      }
    },
    onDeviceStatus(frame) {
      if (!this.device) return
      const payload = (frame && frame.payload) || {}
      if (payload.deviceId === this.device.deviceId) {
        this.device = applyStatus(this.device, frame)
      } else {
        this.device = { ...this.device, sensors: applyStatus(this.device.sensors || [], frame) }
      }
    },
    handleResync() {
      this.load()
    },
    goStock() {
      uni.navigateTo({
        url: `/pages/stock/stock?deviceId=${encodeURIComponent(this.deviceId)}`,
      })
    },

    // ------------------------------------------------------------ 远程控制（feat/mp-device-extra）

    /** 操作人：当前用户 ID（后端只作留痕；缺省时后端按上下文补）。 */
    operator() {
      const info = getUserInfo()
      return info.userId || ''
    },

    /**
     * 下发控制命令（蜂鸣/解锁/校准/重启）。仅监护人可见（RoleGate 已拦），故不在此二次判角色。
     *
     * <p>异步受理：POST 返回 `{commandId, state:PENDING}`，我们记一笔"下发中"，然后刷新事件列表，
     * 由 COMMAND 事件回写终态（后端无命令状态 GET、无命令 WS 推送帧，见 02 第 4 章 + utils/device.js）。
     */
    async onCommand(cmd) {
      if (!this.device) return
      this.cmdSending = cmd
      this.commandHint = ''
      try {
        const res = await sendDeviceCommand(
          this.deviceId,
          cmd,
          cmd === 'BUZZ' ? { durationSec: 5 } : {},
          this.operator(),
        )
        this.issuedCommands.push({ cmd, commandId: res.commandId, state: res.state || 'PENDING' })
        this.refreshEvents()
      } catch (err) {
        // 设备离线 40901 给专属提示；其余（40301/40302/网络）用后端 message
        this.commandHint = err && err.code === CODE.CONFLICT ? '设备离线，无法下发' : (err && err.message) || '下发失败'
      } finally {
        this.cmdSending = ''
      }
    },

    /** 某命令最近一次下发的状态文案（PENDING→成功/失败）。 */
    commandStateOf(cmd) {
      let found = null
      this.issuedCommands.forEach((c) => {
        if (c.cmd === cmd) found = c
      })
      return found ? commandStateLabel(found.state) : ''
    },

    /** 命令状态着色：成功绿 / 失败红 / 下发中灰。 */
    commandStateClass(cmd) {
      const text = this.commandStateOf(cmd)
      if (text === '成功') return 'cmd-state--ok'
      if (text === '失败') return 'cmd-state--fail'
      return 'cmd-state--pending'
    },

    // ------------------------------------------------------------ 设备事件（feat/mp-device-extra）

    /** 加载最近 5 条事件（详情页卡片）。失败仅记日志，不阻断详情页。 */
    async loadEvents() {
      if (!this.deviceId) return
      try {
        const data = await getDeviceEvents(this.deviceId, { page: 1, size: 5 })
        const { list } = parseEventsPage(data)
        this.recentEvents = list.map(toEventView)
        // COMMAND 事件回写已下发命令的终态
        this.recentEvents.forEach((ev) => {
          if (ev.isCommand && ev.commandId) {
            this.issuedCommands.forEach((c) => {
              if (c.commandId === ev.commandId) c.state = ev.commandState
            })
          }
        })
      } catch (err) {
        console.warn('[device-detail] 加载设备事件失败', err && err.code ? err.code : err)
      }
    },

    /** 下发命令后刷事件，以观察 COMMAND 事件终态。 */
    refreshEvents() {
      this.loadEvents()
    },

    /** 去事件全量页。 */
    goEvents() {
      uni.navigateTo({ url: `/pages/device/events?deviceId=${encodeURIComponent(this.deviceId)}` })
    },

    /** 事件行补充文案：FAULT→故障标签；COMMAND→命令+状态；其余为空。 */
    eventDetailText(ev) {
      if (ev.isFault) return ev.faultLabel
      if (ev.isCommand) return `${ev.cmdLabel} · ${ev.commandStateLabel}`
      return ''
    },

    /** 事件类型着色：故障红 / 命令蓝 / 离线灰 / 上线绿。 */
    eventClass(ev) {
      if (ev.isFault) return 'event-type--fault'
      if (ev.isCommand) return 'event-type--command'
      if (ev.isOffline) return 'event-type--off'
      return 'event-type--on'
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  box-sizing: border-box;
}

.state {
  font-size: 26rpx;

  &--on {
    color: $mb-success;
  }

  &--off {
    color: $mb-text-4;
  }
}

/* 远程控制：命令按钮网格 + 每命令状态 */
.cmd-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
}

.cmd-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
}

.cmd-state {
  font-size: 22rpx;

  &--ok {
    color: $mb-success;
  }

  &--fail {
    color: $mb-danger;
  }

  &--pending {
    color: $mb-text-4;
  }
}

.cmd-hint {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: $mb-danger;
}

/* 设备事件：紧凑列表 */
.event-list {
  display: flex;
  flex-direction: column;
}

.event-row {
  display: flex;
  align-items: center;
  padding: 16rpx 0;
  border-bottom: 1rpx solid $mb-border;

  &:last-child {
    border-bottom: none;
  }
}

.event-type {
  flex: 0 0 120rpx;
  font-size: 26rpx;
  font-weight: 600;

  &--on {
    color: $mb-success;
  }

  &--off {
    color: $mb-text-3;
  }

  &--fault {
    color: $mb-danger;
  }

  &--command {
    color: $mb-primary;
  }
}

.event-detail {
  flex: 1;
  font-size: 24rpx;
  color: $mb-text-3;
  padding: 0 16rpx;
}

.event-time {
  flex: 0 0 auto;
  font-size: 22rpx;
  color: $mb-text-4;
}

.event-more {
  margin-top: 12rpx;
  text-align: right;

  &__text {
    font-size: 24rpx;
    color: $mb-primary;
  }
}
</style>

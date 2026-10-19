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
    </template>
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbLoading from '@/components/MbLoading.vue'
import { getDevice } from '@/api/device.js'
import { CODE } from '@/api/request.js'
import { onEvent, onResync, WS_EVENT } from '@/store/ws.js'
import { deviceTypeLabel } from '@/utils/ws-frame.js'
import { applyStatus } from '@/utils/device-status.js'
import { formatDateTime, formatRelative } from '@/utils/datetime.js'

export default {
  name: 'DeviceDetailPage',
  components: { MbCard, MbCell, MbEmpty, MbLoading },
  data() {
    return {
      device: null,
      deviceId: '',
      loading: false,
      error: '',
      offStatus: null,
      offResync: null,
    }
  },
  computed: {
    sensors() {
      return (this.device && this.device.sensors) || []
    },
  },
  onLoad(options) {
    this.deviceId = (options && options.deviceId) || ''
    this.offStatus = onEvent(WS_EVENT.DEVICE_STATUS, this.onDeviceStatus)
    this.offResync = onResync(this.handleResync)
    this.load()
  },
  onUnload() {
    if (this.offStatus) this.offStatus()
    if (this.offResync) this.offResync()
  },
  methods: {
    deviceTypeLabel,
    formatDateTime,
    formatRelative,
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
</style>

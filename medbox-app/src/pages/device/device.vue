<template>
  <view class="page">
    <view class="head">
      <text class="head__title">我的设备</text>
      <text class="head__sub">药箱主控（设备自生成 ID，监护人登记）</text>
    </view>

    <MbCard v-if="isGuardian" title="登记药箱">
      <view class="form-row">
        <input v-model="newDeviceId" class="form-input" placeholder="设备自生成 ID（如 BOXA1001）" />
      </view>
      <view class="form-row">
        <input v-model="newElderId" class="form-input" placeholder="老人用户 ID" />
      </view>
      <MbButton type="primary" size="small" :loading="registering" @click="onRegister">登记设备</MbButton>
      <text v-if="registerHint" class="register-hint">{{ registerHint }}</text>
    </MbCard>

    <MbLoading v-if="loading" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <MbEmpty v-else-if="devices.length === 0" text="暂无设备" />

    <MbCard v-else title="设备列表">
      <MbCell
        v-for="device in devices"
        :key="device.deviceId"
        :title="device.deviceId"
        :label="deviceLabel(device)"
        clickable
        arrow
        @click="goDetail(device)"
      >
        <template #extra>
          <view class="status" :class="device.onlineState === 'ONLINE' ? 'status--on' : 'status--off'">
            <view class="status__dot"></view>
            <text class="status__text">{{ device.onlineState === 'ONLINE' ? '在线' : '离线' }}</text>
          </view>
        </template>
      </MbCell>
    </MbCard>

    <MbDrawer />
    <MbTabBar />
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbLoading from '@/components/MbLoading.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import MbDrawer from '@/components/MbDrawer.vue'
import MbButton from '@/components/MbButton.vue'
import { listDevices, registerDevice } from '@/api/device.js'
import { connect, onEvent, onResync, WS_EVENT } from '@/store/ws.js'
import { getRole, ROLE } from '@/store/user.js'
import { applyStatus } from '@/utils/device-status.js'
import { formatRelative } from '@/utils/datetime.js'

export default {
  name: 'DevicePage',
  components: { MbCard, MbCell, MbEmpty, MbLoading, MbTabBar, MbDrawer, MbButton },
  data() {
    return {
      devices: [],
      loading: false,
      error: '',
      offStatus: null,
      offResync: null,
      newDeviceId: '',
      newElderId: '',
      registering: false,
      registerHint: '',
    }
  },
  computed: {
    isGuardian() {
      return getRole() === ROLE.GUARDIAN
    },
  },
  onLoad() {
    this.offStatus = onEvent(WS_EVENT.DEVICE_STATUS, this.onDeviceStatus)
    this.offResync = onResync(this.handleResync)
    this.load()
  },
  onUnload() {
    if (this.offStatus) this.offStatus()
    if (this.offResync) this.offResync()
  },
  methods: {
    async load() {
      this.loading = true
      this.error = ''
      try {
        this.devices = await listDevices()
        this.connectForGuardian()
      } catch (err) {
        this.error = (err && err.message) || '加载失败'
      } finally {
        this.loading = false
      }
    },
    // 监护人可见的是「一批老人」的设备，订阅主题要从列表归纳 elderId 后传入（老人端 App.vue 已起连自己）
    connectForGuardian() {
      if (getRole() !== ROLE.GUARDIAN) return
      const elderIds = []
      this.devices.forEach((device) => {
        if (device.elderId && elderIds.indexOf(device.elderId) === -1) elderIds.push(device.elderId)
      })
      if (elderIds.length) connect({ elderIds })
    },
    onDeviceStatus(frame) {
      this.devices = applyStatus(this.devices, frame)
    },
    handleResync() {
      this.load()
    },
    deviceLabel(device) {
      const parts = [device.elderId ? `老人 ${device.elderId}` : '药箱主控']
      if (device.lastHeartbeat) parts.push(`心跳 ${formatRelative(device.lastHeartbeat)}`)
      return parts.join(' · ')
    },
    async onRegister() {
      if (!this.newDeviceId.trim() || !this.newElderId.trim()) {
        this.registerHint = '请填写设备 ID 和老人用户 ID'
        return
      }
      this.registering = true
      this.registerHint = ''
      try {
        await registerDevice({ deviceId: this.newDeviceId.trim(), elderId: this.newElderId.trim() })
        this.registerHint = '登记成功，设备初始为离线'
        this.newDeviceId = ''
        this.newElderId = ''
        await this.load()
      } catch (err) {
        this.registerHint = err && err.code === 40901 ? '该设备编号已登记' : (err && err.message) || '登记失败'
      } finally {
        this.registering = false
      }
    },
    goDetail(device) {
      uni.navigateTo({ url: `/pages/device/detail?deviceId=${encodeURIComponent(device.deviceId)}` })
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx calc(110rpx + env(safe-area-inset-bottom) + 24rpx);
  box-sizing: border-box;
}

.head {
  display: flex;
  flex-direction: column;
  margin-bottom: $mb-gap;

  &__title {
    font-size: 40rpx;
    font-weight: 600;
    color: $mb-text;
  }

  &__sub {
    margin-top: 8rpx;
    font-size: 26rpx;
    color: $mb-text-3;
  }
}

.form-row {
  margin-bottom: 16rpx;
}

.form-input {
  width: 100%;
  padding: 16rpx 20rpx;
  box-sizing: border-box;
  background: #fff;
  border: 1rpx solid $mb-border;
  border-radius: 12rpx;
  font-size: 28rpx;
  color: $mb-text;
}

.register-hint {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: $mb-text-3;
}

.status {
  display: flex;
  align-items: center;

  &__dot {
    width: 16rpx;
    height: 16rpx;
    border-radius: 50%;
    margin-right: 8rpx;
  }

  &--on &__dot {
    background: $mb-success;
  }

  &--off &__dot {
    background: $mb-text-4;
  }

  &--on &__text {
    color: $mb-success;
  }

  &--off &__text {
    color: $mb-text-4;
  }

  &__text {
    font-size: 26rpx;
  }
}
</style>

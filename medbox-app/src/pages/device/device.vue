<template>
  <view class="page">
    <view class="head">
      <text class="head__title">我的设备</text>
      <text class="head__sub">药箱主控（设备自生成 ID，监护人登记）</text>
    </view>

    <MbCard v-if="isGuardian" title="登记药箱">
      <MbField
        v-model="newDeviceId"
        label="设备自生成 ID"
        placeholder="如 BOXA1001"
        :error="deviceIdError"
      />
      <MbField
        v-model="newElderId"
        label="老人用户 ID"
        placeholder="请输入已绑定老人的用户 ID"
        :error="elderIdError"
      />
      <text class="register-tip">登记前需与老人建立 ACTIVE 监护关系；PENDING 无法登记。</text>
      <MbButton
        type="primary"
        size="small"
        :loading="registering"
        :disabled="!canRegister"
        @click="onRegister"
      >登记设备</MbButton>
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
import MbField from '@/components/MbField.vue'
import { listDevices, registerDevice } from '@/api/device.js'
import { CODE } from '@/api/request.js'
import { getElderProfile } from '@/api/user.js'
import { connect, onEvent, onResync, WS_EVENT } from '@/store/ws.js'
import { getRole, ROLE } from '@/store/user.js'
import { applyStatus } from '@/utils/device-status.js'
import { collectElderIds, buildElderProfiles, deviceElderLabel } from '@/utils/device-elder.js'
import { formatRelative } from '@/utils/datetime.js'

const DEVICE_ID_RE = /^[A-Za-z0-9_-]{4,64}$/
const REGISTER_HINTS = {
  [CODE.BAD_REQUEST]: '设备 ID 格式不合法',
  [CODE.FORBIDDEN]: '无权限登记，请确认已与该老人建立监护关系',
  [CODE.GUARDIAN_PENDING]: '监护关系未生效，请等待老人确认',
  [CODE.CONFLICT]: '该设备编号已登记',
}

export default {
  name: 'DevicePage',
  components: { MbCard, MbCell, MbEmpty, MbLoading, MbTabBar, MbDrawer, MbButton, MbField },
  data() {
    return {
      devices: [],
      loading: false,
      error: '',
      offStatus: null,
      offResync: null,
      newDeviceId: '',
      newElderId: '',
      deviceIdError: '',
      elderIdError: '',
      elderProfiles: {},
      registering: false,
      registerHint: '',
    }
  },
  computed: {
    isGuardian() {
      return getRole() === ROLE.GUARDIAN
    },
    canRegister() {
      return !!(this.newDeviceId.trim() && this.newElderId.trim() && !this.deviceIdError && !this.elderIdError)
    },
  },
  watch: {
    newDeviceId(value) {
      const text = String(value || '').trim()
      this.deviceIdError = !text || DEVICE_ID_RE.test(text) ? '' : '4~64 位字母、数字、下划线或短横线'
    },
    newElderId(value) {
      this.elderIdError = String(value || '').trim() ? '' : '请输入老人用户 ID'
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
        await this.loadElderProfiles()
        this.connectForGuardian()
      } catch (err) {
        this.error = (err && err.message) || '加载失败'
      } finally {
        this.loading = false
      }
    },
    /** 老人姓名是增强信息：并发拉取、单个失败静默回退 ID，不阻断设备列表。 */
    async loadElderProfiles() {
      const elderIds = collectElderIds(this.devices)
      if (!elderIds.length) {
        this.elderProfiles = {}
        return
      }
      const results = await Promise.all(elderIds.map((elderId) =>
        getElderProfile(elderId, { silent: true })
          .then((value) => ({ status: 'fulfilled', value }))
          .catch(() => ({ status: 'rejected' })),
      ))
      this.elderProfiles = buildElderProfiles(elderIds, results)
    },
    // 监护人可见的是「一批老人」的设备，订阅主题要从列表归纳 elderId 后传入（老人端 App.vue 已起连自己）
    connectForGuardian() {
      if (getRole() !== ROLE.GUARDIAN) return
      const elderIds = collectElderIds(this.devices)
      if (elderIds.length) connect({ elderIds })
    },
    onDeviceStatus(frame) {
      this.devices = applyStatus(this.devices, frame)
    },
    handleResync() {
      this.load()
    },
    deviceLabel(device) {
      const parts = [deviceElderLabel(device, this.elderProfiles)]
      if (device.lastHeartbeat) parts.push(`心跳 ${formatRelative(device.lastHeartbeat)}`)
      return parts.join(' · ')
    },
    async onRegister() {
      const deviceId = this.newDeviceId.trim()
      const elderId = this.newElderId.trim()
      this.deviceIdError = DEVICE_ID_RE.test(deviceId) ? '' : '4~64 位字母、数字、下划线或短横线'
      this.elderIdError = elderId ? '' : '请输入老人用户 ID'
      if (this.deviceIdError || this.elderIdError) {
        this.registerHint = '请检查填写内容'
        return
      }
      this.registering = true
      this.registerHint = ''
      try {
        await registerDevice({ deviceId, elderId })
        this.registerHint = '登记成功，设备初始为离线'
        this.newDeviceId = ''
        this.newElderId = ''
        await this.load()
      } catch (err) {
        const code = err && err.code
        this.registerHint = REGISTER_HINTS[code] || (err && err.message) || '登记失败'
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

.register-tip {
  display: block;
  margin: 4rpx 0 16rpx;
  font-size: 24rpx;
  line-height: 1.5;
  color: $mb-text-3;
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

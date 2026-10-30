<template>
  <view class="page">
    <MbLoading v-if="loading" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <template v-else-if="view">
      <MbCard title="抓拍原图">
        <view v-if="view.expired" class="img-fallback">
          <text class="img-fallback__icon">⏱️</text>
          <text class="img-fallback__text">原图已过期，无法访问</text>
        </view>
        <view v-else-if="captureFileUrl" class="imgwrap">
          <image
            class="img"
            :src="captureFileUrl"
            mode="widthFix"
            @error="onImageError"
          />
        </view>
        <view v-else class="img-fallback">
          <text class="img-fallback__icon">📷</text>
          <text class="img-fallback__text">{{ captureError || '原图加载中…' }}</text>
        </view>
        <view v-if="view.uncertain" class="badge">待确认（低置信度）</view>
      </MbCard>

      <MbCard title="抓拍信息">
        <MbCell title="设备" :value="view.deviceId || '-'" />
        <MbCell title="原因" :value="view.reasonLabel" />
        <MbCell v-if="view.faceResultLabel" title="服药结论" :value="view.faceResultLabel" />
        <MbCell
          :title="view.confidence != null ? '置信度' : '置信度'"
          :value="view.confidence != null ? view.confidence.toFixed(2) : '-'"
        />
        <MbCell title="抓拍时间" :value="view.capturedAtText" />
        <MbCell title="过期时间" :value="view.expireAtText || '-'" />
      </MbCard>

      <RoleGate entry="writeCapture">
        <view class="actions">
          <MbButton type="danger" block :loading="deleting" @click="openDelete">
            删除抓拍
          </MbButton>
        </view>
      </RoleGate>
    </template>

    <MbModal
      v-model="showDelete"
      title="删除抓拍"
      confirm-text="确认删除"
      confirm-type="danger"
      :confirm-loading="deleting"
      :close-on-confirm="false"
      @confirm="onDeleteConfirm"
    >
      <view class="modal-body">
        <text class="modal-body__text">删除后该抓拍原图与记录将无法恢复，确定要删除吗？</text>
        <text v-if="deleteError" class="modal-body__err">{{ deleteError }}</text>
      </view>
    </MbModal>

    <MbTabBar />
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbLoading from '@/components/MbLoading.vue'
import MbModal from '@/components/MbModal.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import RoleGate from '@/components/RoleGate.vue'
import { deleteCapture, getCapture } from '@/api/capture.js'
import { CODE } from '@/api/request.js'
import { BASE_URL } from '@/config/index.js'
import { buildCaptureFileUrl, parseCapture, toCaptureView } from '@/utils/capture.js'

function errorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权访问或抓拍不存在'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  if (err && err.code === CODE.NOT_FOUND) return '抓拍不存在或已删除'
  return (err && err.message) || '加载失败'
}

function deleteErrorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权限删除（仅监护人可操作）'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  if (err && err.code === CODE.NOT_FOUND) return '抓拍不存在或已删除'
  return (err && err.message) || '删除失败，请稍后再试'
}

export default {
  name: 'CaptureViewerPage',
  components: {
    MbButton, MbCard, MbCell, MbEmpty, MbLoading, MbModal, MbTabBar, RoleGate,
  },
  data() {
    return {
      captureId: '',
      view: null,
      captureFileUrl: '',
      captureError: '',
      loading: false,
      error: '',
      showDelete: false,
      deleting: false,
      deleteError: '',
    }
  },
  onLoad(options) {
    this.captureId = (options && options.captureId) || ''
    if (!this.captureId) {
      this.error = '缺少抓拍编号'
      return
    }
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      this.error = ''
      try {
        const data = await getCapture(this.captureId, { silent: true })
        const parsed = parseCapture(data)
        this.view = toCaptureView(parsed)
        if (this.view.expired) {
          this.captureFileUrl = ''
        } else if (parsed.fileToken) {
          this.captureFileUrl = buildCaptureFileUrl(this.captureId, parsed.fileToken, BASE_URL)
        }
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    onImageError() {
      this.captureFileUrl = ''
      this.captureError = '原图加载失败，请重试'
    },
    openDelete() {
      this.deleteError = ''
      this.showDelete = true
    },
    onDeleteConfirm() {
      if (this.deleting) return
      this.deleting = true
      this.deleteError = ''
      deleteCapture(this.captureId, { silent: true })
        .then(() => {
          this.showDelete = false
          uni.showToast({ title: '已删除', icon: 'success' })
          // 返回列表并触发其 onShow 刷新
          uni.navigateBack({
            delta: 1,
            fail: () => {
              uni.redirectTo({ url: '/pages/record/record' })
            },
          })
        })
        .catch((err) => {
          this.deleteError = deleteErrorText(err)
        })
        .finally(() => {
          this.deleting = false
        })
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx calc(150rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}

.imgwrap {
  width: 100%;
  border-radius: $mb-radius;
  overflow: hidden;
  background: $mb-bg;
}

.img {
  width: 100%;
  display: block;
}

.img-fallback {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  height: 360rpx;
  border-radius: $mb-radius;
  background: $mb-bg;
  border: 2rpx dashed $mb-border;
  justify-content: center;

  &__icon {
    font-size: 80rpx;
    opacity: 0.4;
  }

  &__text {
    margin-top: 20rpx;
    font-size: 26rpx;
    color: $mb-text-3;
  }
}

.badge {
  margin-top: 16rpx;
  padding: 6rpx 20rpx;
  border-radius: $mb-radius-pill;
  font-size: 24rpx;
  color: #9a3412;
  background: #fed7aa;
  align-self: flex-start;
}

.actions {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  padding: 20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 -8rpx 24rpx rgba(229, 72, 77, 0.08);
}

.modal-body {
  &__text {
    display: block;
    font-size: 28rpx;
    color: $mb-text-2;
    line-height: 1.6;
  }

  &__err {
    display: block;
    margin-top: 12rpx;
    font-size: 26rpx;
    color: $mb-danger;
    line-height: 1.5;
  }
}
</style>

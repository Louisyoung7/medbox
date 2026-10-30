<template>
  <view class="page">
    <view class="head">
      <text class="head__title">抓拍留证</text>
      <text class="head__sub">摄像头留证原图（仅本人与监护人可见）</text>
    </view>

    <!-- reason 分段筛选：全部 / 正常服药(NORMAL) / 主动留证(MANUAL=只看异常)。 -->
    <view class="seg">
      <view
        v-for="opt in reasonOptions"
        :key="opt.value"
        class="seg__item"
        :class="{ 'seg__item--on': reason === opt.value }"
        hover-class="seg__item--hover"
        @click="selectReason(opt.value)"
      >
        <text class="seg__text">{{ opt.label }}</text>
      </view>
    </view>

    <view v-if="summaryVisible" class="summary">共 {{ total }} 张抓拍</view>

    <MbEmpty v-if="error" :text="error" />

    <MbEmpty v-else-if="!loading && captures.length === 0" text="暂无抓拍" />

    <template v-else>
      <MbCard v-for="item in captures" :key="item.captureId" @click="goViewer(item)">
        <view class="row">
          <view class="thumb">
            <image
              v-if="!thumbErrors[item.captureId] && item.fileToken"
              class="thumb__img"
              :src="item.thumbnailUrl"
              mode="aspectFill"
              @error="onThumbError(item.captureId)"
            />
            <view v-else class="thumb__box">
              <text class="thumb__icon">📷</text>
            </view>
            <view v-if="item.uncertain" class="thumb__badge">待确认</view>
            <view v-if="item.expired" class="thumb__badge thumb__badge--expired">已过期</view>
          </view>
          <view class="meta">
            <view class="meta__top">
              <text class="meta__reason">{{ item.reasonLabel }}</text>
              <text v-if="item.faceResultLabel" class="meta__result">{{ item.faceResultLabel }}</text>
            </view>
            <text class="meta__time">{{ item.capturedAtText }}</text>
            <text v-if="item.deviceId" class="meta__device">{{ item.deviceId }}</text>
          </view>
          <text class="arrow">›</text>
        </view>
      </MbCard>

      <view v-if="canLoadMore" class="more">
        <MbButton type="ghost" block :loading="loadingMore" @click="loadMore">加载更多</MbButton>
      </view>
    </template>

    <view v-if="loading && captures.length === 0" class="loading-tip">加载中…</view>

    <MbTabBar />
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import { listCaptures } from '@/api/capture.js'
import { CODE } from '@/api/request.js'
import { BASE_URL } from '@/config/index.js'
import { buildCaptureFileUrl, parseCapturesPage, toCaptureView } from '@/utils/capture.js'

const PAGE_SIZE = 20

const REASON_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'NORMAL', label: '正常服药' },
  { value: 'MANUAL', label: '主动留证' },
]

/** 列表 / 加载失败文案按 code 细分（请求已 silent，页面统一自绘）。 */
function errorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权访问或该老人不存在'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '加载失败'
}

export default {
  name: 'CaptureListPage',
  components: { MbButton, MbCard, MbEmpty, MbTabBar },
  data() {
    return {
      elderId: '',
      reason: '',
      reasonOptions: REASON_OPTIONS,
      captures: [],
      total: 0,
      page: 1,
      loading: false,
      loadingMore: false,
      loaded: false,
      error: '',
      thumbErrors: {},
    }
  },
  computed: {
    summaryVisible() {
      return !this.error && this.total > 0
    },
    canLoadMore() {
      return this.loaded && !this.error && this.captures.length < this.total
    },
  },
  onLoad(options) {
    this.elderId = (options && options.elderId) || ''
    if (!this.elderId) {
      this.error = '缺少老人信息，无法查看抓拍'
      return
    }
    this.load()
  },
  onShow() {
    if (this.loaded && !this.error) this.reload()
  },
  methods: {
    async reload() {
      this.page = 1
      this.captures = []
      this.thumbErrors = {}
      await this.load()
    },
    async load() {
      this.loading = true
      this.error = ''
      try {
        await this.fetchPage(1)
        this.loaded = true
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    async fetchPage(page) {
      const data = await listCaptures(
        this.elderId,
        {
          reason: this.reason || undefined,
          page,
          size: PAGE_SIZE,
        },
        { silent: true },
      )
      const result = parseCapturesPage(data)
      const list = result.list.map((c) => {
        const view = toCaptureView(c)
        view.thumbnailUrl = view.fileToken
          ? buildCaptureFileUrl(view.captureId, view.fileToken, BASE_URL)
          : ''
        return view
      })
      if (page === 1) {
        this.captures = list
      } else {
        this.captures = this.captures.concat(list)
      }
      this.total = result.total
      this.page = page
    },
    async loadMore() {
      if (this.loadingMore) return
      this.loadingMore = true
      try {
        await this.fetchPage(this.page + 1)
      } catch (err) {
        uni.showToast({ title: errorText(err), icon: 'none' })
      } finally {
        this.loadingMore = false
      }
    },
    selectReason(value) {
      if (value === this.reason) return
      this.reason = value
      this.reload()
    },
    onThumbError(captureId) {
      this.$set(this.thumbErrors, captureId, true)
    },
    goViewer(item) {
      uni.navigateTo({
        url: `/pages/capture/viewer?captureId=${encodeURIComponent(item.captureId)}`,
      })
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

.seg {
  display: flex;
  padding: 6rpx;
  margin-bottom: 16rpx;
  border-radius: $mb-radius;
  background: $mb-bg;
  border: 2rpx solid $mb-border;

  &__item {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    height: 64rpx;
    border-radius: $mb-radius-sm;
    transition: background 0.15s ease;

    &--on {
      background: $mb-primary;
    }

    &--hover {
      opacity: 0.8;
    }
  }

  &__text {
    font-size: 28rpx;
    color: $mb-text-2;

    .seg__item--on & {
      color: #fff;
      font-weight: 500;
    }
  }
}

.summary {
  margin-bottom: 16rpx;
  font-size: 26rpx;
  color: $mb-text-3;
}

.row {
  display: flex;
  align-items: center;
}

.thumb {
  position: relative;
  width: 140rpx;
  height: 140rpx;
  margin-right: 24rpx;
  border-radius: $mb-radius;
  overflow: hidden;
  background: $mb-bg;
  flex-shrink: 0;

  &__img {
    width: 100%;
    height: 100%;
    display: block;
  }

  &__box {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
  }

  &__icon {
    font-size: 56rpx;
    opacity: 0.4;
  }

  &__badge {
    position: absolute;
    left: 0;
    bottom: 0;
    padding: 2rpx 12rpx;
    font-size: 20rpx;
    color: #9a3412;
    background: #fed7aa;
    border-top-right-radius: $mb-radius-sm;

    &--expired {
      color: #fff;
      background: $mb-danger;
    }
  }
}

.meta {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;

  &__top {
    display: flex;
    align-items: center;
  }

  &__reason {
    font-size: 30rpx;
    font-weight: 500;
    color: $mb-text;
  }

  &__result {
    margin-left: 16rpx;
    padding: 2rpx 14rpx;
    border-radius: $mb-radius-pill;
    font-size: 22rpx;
    color: $mb-primary;
    background: $mb-primary-bg;
  }

  &__time {
    margin-top: 10rpx;
    font-size: 26rpx;
    color: $mb-text-3;
  }

  &__device {
    margin-top: 4rpx;
    font-size: 24rpx;
    color: $mb-text-3;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.arrow {
  margin-left: 12rpx;
  font-size: 40rpx;
  color: $mb-text-3;
}

.more {
  margin-top: 8rpx;
}

.loading-tip {
  padding: 40rpx 0;
  text-align: center;
  font-size: 26rpx;
  color: $mb-text-3;
}
</style>

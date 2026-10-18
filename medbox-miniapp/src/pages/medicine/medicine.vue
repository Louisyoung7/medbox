<template>
  <view class="page">
    <!-- 搜索栏：300ms 防抖 + 键盘"搜索"键，两种都触发；清除即回到全量 -->
    <view class="search">
      <view class="search__box" :class="{ 'search__box--focus': searchFocus }">
        <input
          class="search__input"
          :value="keyword"
          placeholder="搜索药品名称"
          confirm-type="search"
          placeholder-class="search__placeholder"
          @input="onKeywordInput"
          @confirm="searchNow"
          @focus="searchFocus = true"
          @blur="searchFocus = false"
        />
        <text v-if="keyword" class="search__clear" @click="clearKeyword">×</text>
      </view>
      <text class="search__btn" @click="searchNow">搜索</text>
    </view>

    <view v-if="summaryVisible" class="summary">
      <text class="summary__count">共 {{ total }} 种药品</text>
      <text v-if="keyword" class="summary__pill" @click="clearKeyword">“{{ keyword }}” ×</text>
    </view>

    <MbLoading v-if="loading && medicines.length === 0" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <MbEmpty v-else-if="!loading && medicines.length === 0" :text="emptyText" />

    <MbCard v-else title="药品列表">
      <MbCell
        v-for="medicine in medicines"
        :key="medicine.medicineId"
        :title="medicine.name"
        :label="medicineLabel(medicine)"
        clickable
        arrow
        @click="goDetail(medicine)"
      >
        <template #extra>
          <view class="owner" :class="medicine.ownerElderId ? 'owner--private' : 'owner--public'">
            <text class="owner__text">{{ ownerLabel(medicine) }}</text>
          </view>
        </template>
      </MbCell>
    </MbCard>

    <view v-if="hasMore && !error" class="more">
      <MbButton type="ghost" :loading="loading" @click="loadMore">加载更多</MbButton>
    </view>

    <view v-if="canWrite" class="create">
      <MbButton type="primary" block @click="goCreate">新建药品</MbButton>
    </view>

    <MbTabBar />
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbLoading from '@/components/MbLoading.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import { listMedicines } from '@/api/medicine.js'
import { CODE } from '@/api/request.js'
import { getRole, getUserId } from '@/store/user.js'
import {
  canWriteMedicine,
  medicineLabel,
  medicineOwnerLabel,
  parseMedicinePage,
} from '@/utils/medicine.js'

/** 每页条数（02 的 1.：`size` 默认 20、上限 100）。 */
const PAGE_SIZE = 20

/** 搜索防抖（毫秒）：避免每敲一个字发一次请求。 */
const SEARCH_DEBOUNCE_MS = 300

/** 列表错误文案按 code 细分（请求已 silent，这里统一自绘）。 */
function errorText(err) {
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '加载失败'
}

export default {
  name: 'MedicinePage',
  components: { MbButton, MbCard, MbCell, MbEmpty, MbLoading, MbTabBar },
  data() {
    return {
      keyword: '',
      searchFocus: false,
      medicines: [],
      total: 0,
      page: 1,
      loading: false,
      error: '',
      canWrite: false,
      myUserId: '',
      loaded: false,
      debounceTimer: null,
    }
  },
  computed: {
    hasMore() {
      return this.medicines.length < this.total
    },
    summaryVisible() {
      return !this.error && this.total > 0
    },
    emptyText() {
      return this.keyword ? `未找到与“${this.keyword}”相关的药品` : '暂无药品'
    },
  },
  onLoad() {
    this.canWrite = canWriteMedicine(getRole())
    this.myUserId = getUserId()
    this.fetchPage(true)
  },
  onShow() {
    // 从详情 / 编辑页返回时刷新（新建、编辑、删除都会改变列表）
    if (this.loaded) this.fetchPage(true)
  },
  onUnload() {
    this.clearDebounce()
  },
  methods: {
    medicineLabel,
    ownerLabel(medicine) {
      return medicineOwnerLabel(medicine, this.myUserId)
    },
    onKeywordInput(e) {
      this.keyword = e.detail.value
      this.clearDebounce()
      this.debounceTimer = setTimeout(() => {
        this.debounceTimer = null
        this.fetchPage(true)
      }, SEARCH_DEBOUNCE_MS)
    },
    searchNow() {
      this.clearDebounce()
      this.fetchPage(true)
    },
    clearKeyword() {
      this.clearDebounce()
      if (!this.keyword) return
      this.keyword = ''
      this.fetchPage(true)
    },
    clearDebounce() {
      if (this.debounceTimer) {
        clearTimeout(this.debounceTimer)
        this.debounceTimer = null
      }
    },
    async fetchPage(replace) {
      if (this.loading) return
      this.loading = true
      if (replace) this.error = ''
      try {
        const data = await listMedicines(
          { keyword: this.keyword, page: this.page, size: PAGE_SIZE },
          { silent: true },
        )
        const page = parseMedicinePage(data)
        this.total = page.total
        this.medicines = replace ? page.list : this.medicines.concat(page.list)
        this.loaded = true
      } catch (err) {
        if (!replace) {
          // 加载更多失败：回退页码、保留已有列表，只 toast
          this.page -= 1
          this.loading = false
          return
        }
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    loadMore() {
      if (this.loading || !this.hasMore) return
      this.page += 1
      this.fetchPage(false)
    },
    goDetail(medicine) {
      uni.navigateTo({
        url: `/pages/medicine/detail?medicineId=${encodeURIComponent(medicine.medicineId)}`,
      })
    },
    goCreate() {
      uni.navigateTo({ url: '/pages/medicine/edit' })
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

.search {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-bottom: $mb-gap;

  &__box {
    flex: 1;
    display: flex;
    align-items: center;
    height: 80rpx;
    padding: 0 24rpx;
    border: 2rpx solid $mb-border;
    border-radius: $mb-radius-pill;
    background: $mb-card-bg;
    transition: border-color 0.2s;

    &--focus {
      border-color: $mb-primary;
    }
  }

  &__input {
    flex: 1;
    height: 80rpx;
    font-size: 28rpx;
    color: $mb-text;
  }

  &__placeholder {
    color: $mb-placeholder;
    font-size: 28rpx;
  }

  &__clear {
    padding: 0 8rpx;
    font-size: 36rpx;
    color: $mb-text-4;
    line-height: 1;
  }

  &__btn {
    font-size: 28rpx;
    color: $mb-primary;
    font-weight: 500;
  }
}

.summary {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 16rpx;

  &__count {
    font-size: 26rpx;
    color: $mb-text-3;
  }

  &__pill {
    display: inline-flex;
    align-items: center;
    height: 44rpx;
    padding: 0 16rpx;
    border-radius: $mb-radius-pill;
    background: $mb-primary-bg;
    font-size: 24rpx;
    color: $mb-primary;
  }
}

.owner {
  display: inline-flex;
  align-items: center;
  height: 40rpx;
  padding: 0 16rpx;
  border-radius: $mb-radius-pill;

  &--public {
    background: $mb-primary-bg;
  }

  &--private {
    background: $mb-border;
  }

  &__text {
    font-size: 22rpx;
  }

  &--public &__text {
    color: $mb-primary;
  }

  &--private &__text {
    color: $mb-text-3;
  }
}

.more {
  margin-top: $mb-gap;
  text-align: center;
}

.create {
  margin-top: $mb-gap;
}
</style>

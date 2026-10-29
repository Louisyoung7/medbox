<template>
  <view class="page">
    <!-- 设备头：编号 + 在线徽标（设备信息拉不到时降级为仅编号） -->
    <view class="device-head">
      <text class="device-head__id">药箱 {{ deviceId || '-' }}</text>
      <view v-if="device" class="state" :class="online ? 'state--on' : 'state--off'">
        <view class="state__dot"></view>
        <text class="state__text">{{ online ? '在线' : '离线' }}</text>
      </view>
    </view>

    <view v-if="summaryVisible" class="summary">共 {{ stocks.length }} 种药品</view>

    <MbLoading v-if="loading && stocks.length === 0" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <MbEmpty v-else-if="!loading && stocks.length === 0" text="暂无库存记录" />

    <MbCard v-else title="库存清单">
      <MbCell
        v-for="row in stocks"
        :key="row.stockId || row.medicineId"
        :title="row.name"
        :label="rowLabel(row)"
        clickable
        arrow
        @click="goEdit(row)"
      />
    </MbCard>

    <view v-if="canWrite" class="entry">
      <MbButton type="primary" block @click="goCreate">录入库存</MbButton>
    </view>
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbLoading from '@/components/MbLoading.vue'
import { getDevice } from '@/api/device.js'
import { listMedicines } from '@/api/medicine.js'
import { listStocks } from '@/api/stock.js'
import { CODE } from '@/api/request.js'
import { parseMedicinePage } from '@/utils/medicine.js'
import {
  joinStockMedicine,
  parseStocks,
  stockQuantityText,
  toMedicineMap,
} from '@/utils/stock.js'
import { canWriteMedicine } from '@/utils/medicine.js'
import { getRole } from '@/store/user.js'

/** 错误文案按 code 细分（请求已 silent，页面统一自绘）。 */
function errorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权访问或设备不存在'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '加载失败'
}

export default {
  name: 'StockPage',
  components: { MbButton, MbCard, MbCell, MbEmpty, MbLoading },
  data() {
    return {
      deviceId: '',
      device: null,
      stocks: [],
      medicineMap: {},
      loading: false,
      error: '',
      canWrite: false,
      loaded: false,
    }
  },
  computed: {
    summaryVisible() {
      return !this.error && this.stocks.length > 0
    },
    online() {
      return !!(this.device && this.device.onlineState === 'ONLINE')
    },
  },
  onLoad(options) {
    this.deviceId = (options && options.deviceId) || ''
    this.canWrite = canWriteMedicine(getRole())
    this.load()
  },
  onShow() {
    // 从录入 / 补药页返回时刷新（数量与日期都可能已变）
    if (this.loaded) this.load()
  },
  methods: {
    async load() {
      if (!this.deviceId) {
        this.error = '缺少设备编号'
        return
      }
      this.loading = true
      this.error = ''
      try {
        // 设备信息只用于头部徽标，拉不到不阻塞清单（降级为仅编号）
        this.device = await getDevice(this.deviceId, { silent: true }).catch(() => null)
        const stocks = await listStocks(this.deviceId, { silent: true })
        // 药品名 join：字典拉不到不阻塞清单，join 回退显示 medicineId
        const medicines = await this.loadMedicines()
        this.medicineMap = toMedicineMap(medicines)
        this.stocks = parseStocks(stocks).map((row) => joinStockMedicine(row, this.medicineMap))
        this.loaded = true
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    async loadMedicines() {
      try {
        // 演示规模一页足够；超出部分 join 回退显示 medicineId（见 utils/stock.js）
        const page = parseMedicinePage(
          await listMedicines({ page: 1, size: 100 }, { silent: true }),
        )
        return page.list
      } catch (err) {
        return []
      }
    },
    rowLabel(row) {
      const parts = [stockQuantityText(row, this.medicineMap)]
      // DATE 列无时刻，直接展示后端返回的 YYYY-MM-DD，不做 UTC 换算（02 的 1. 时区口径）
      if (row.productionDate) parts.push(`生产 ${row.productionDate}`)
      parts.push(row.expiryDate ? `有效期至 ${row.expiryDate}` : '未填有效期')
      return parts.join(' · ')
    },
    goEdit(row) {
      uni.navigateTo({
        url: `/pages/stock/edit?deviceId=${encodeURIComponent(this.deviceId)}` +
          `&medicineId=${encodeURIComponent(row.medicineId)}`,
      })
    },
    goCreate() {
      uni.navigateTo({ url: `/pages/stock/edit?deviceId=${encodeURIComponent(this.deviceId)}` })
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

.device-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $mb-gap;

  &__id {
    font-size: 30rpx;
    font-weight: 600;
    color: $mb-text;
  }
}

.state {
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

.summary {
  margin-bottom: 16rpx;
  font-size: 26rpx;
  color: $mb-text-3;
}

.tag {
  display: inline-flex;
  align-items: center;
  height: 40rpx;
  padding: 0 16rpx;
  border-radius: $mb-radius-pill;

  &__text {
    font-size: 22rpx;
  }

  &--ok {
    background: $mb-primary-bg;

    .tag__text {
      color: $mb-primary;
    }
  }

  &--unknown {
    background: $mb-border;

    .tag__text {
      color: $mb-text-3;
    }
  }
}

.entry {
  margin-top: $mb-gap;
}
</style>

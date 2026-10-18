<template>
  <view class="page">
    <MbLoading v-if="loading" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <template v-else-if="medicine">
      <MbCard title="药品档案">
        <view class="name">{{ medicine.name }}</view>
        <MbCell title="规格" :value="medicine.spec || '-'" />
        <MbCell
          title="默认单次剂量"
          :value="[medicine.dosePerTime, medicine.unit].filter(Boolean).join(' ') || '-'"
        />
        <MbCell title="归属" :value="ownerText" />
        <MbCell title="创建时间" :value="formatDateTime(medicine.createdAt)" />
      </MbCard>

      <MbCard title="禁忌">
        <view class="contraindication">
          <text class="contraindication__text">{{ medicine.contraindication || '暂无' }}</text>
        </view>
      </MbCard>

      <MbCard title="储存条件">
        <view class="storage">
          <text class="storage__text">{{ medicine.storageCond || '暂无' }}</text>
        </view>
      </MbCard>

      <view v-if="canWrite" class="actions">
        <MbButton type="danger" class="actions__delete" @click="showDelete = true">删除</MbButton>
        <MbButton type="primary" class="actions__edit" @click="goEdit">编辑</MbButton>
      </view>
    </template>

    <MbModal
      v-model="showDelete"
      title="删除药品"
      confirm-type="danger"
      confirm-text="删除"
      :confirm-loading="deleting"
      :close-on-confirm="false"
      @confirm="onDeleteConfirm"
    >
      <view class="delete-body">
        <text class="delete-body__text">删除后不可恢复，确定删除“{{ medicine && medicine.name }}”吗？</text>
        <text v-if="deleteError" class="delete-body__err">{{ deleteError }}</text>
      </view>
    </MbModal>
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbLoading from '@/components/MbLoading.vue'
import MbModal from '@/components/MbModal.vue'
import { deleteMedicine, getMedicine } from '@/api/medicine.js'
import { CODE } from '@/api/request.js'
import { getRole, getUserId } from '@/store/user.js'
import { canWriteMedicine } from '@/utils/medicine.js'
import { formatDateTime } from '@/utils/datetime.js'

/** 详情加载失败文案按 code 细分（请求已 silent）。 */
function errorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权访问或药品不存在'
  if (err && err.code === CODE.NOT_FOUND) return '药品不存在或已删除'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '加载失败'
}

/** 删除失败文案：40901 是药品域最有信息量的一个码（被库存引用）。 */
function deleteErrorText(err) {
  if (err && err.code === CODE.CONFLICT) return '该药品已被药箱库存引用，无法删除'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '删除失败，请稍后再试'
}

export default {
  name: 'MedicineDetailPage',
  components: { MbButton, MbCard, MbCell, MbEmpty, MbLoading, MbModal },
  data() {
    return {
      medicineId: '',
      medicine: null,
      loading: false,
      error: '',
      canWrite: false,
      myUserId: '',
      showDelete: false,
      deleting: false,
      deleteError: '',
    }
  },
  computed: {
    ownerText() {
      const owner = this.medicine && this.medicine.ownerElderId
      if (!owner) return '公共药品（全体可见）'
      if (this.myUserId && owner === this.myUserId) return '我的私有药品'
      return `私有 · ${owner}`
    },
  },
  onLoad(options) {
    this.medicineId = (options && options.medicineId) || ''
    this.canWrite = canWriteMedicine(getRole())
    this.myUserId = getUserId()
    this.load()
  },
  methods: {
    formatDateTime,
    async load() {
      if (!this.medicineId) {
        this.error = '缺少药品编号'
        return
      }
      this.loading = true
      this.error = ''
      try {
        this.medicine = await getMedicine(this.medicineId, { silent: true })
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    goEdit() {
      uni.navigateTo({
        url: `/pages/medicine/edit?medicineId=${encodeURIComponent(this.medicineId)}`,
      })
    },
    onDeleteConfirm() {
      if (this.deleting) return
      this.deleting = true
      this.deleteError = ''
      deleteMedicine(this.medicineId, { silent: true })
        .then(() => {
          uni.showToast({ title: '已删除', icon: 'success' })
          uni.navigateBack()
        })
        .catch((err) => {
          // 弹窗开着（closeOnConfirm=false），让用户看清失败原因后重试或取消
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

.name {
  font-size: 38rpx;
  font-weight: 600;
  color: $mb-text;
  margin-bottom: 16rpx;
}

.contraindication {
  padding: 20rpx 24rpx;
  border-left: 8rpx solid $mb-danger;
  background: #fdecec;
  border-radius: $mb-radius-sm;

  &__text {
    font-size: 28rpx;
    color: $mb-text-2;
    line-height: 1.6;
    white-space: pre-wrap;
  }
}

.storage {
  &__text {
    font-size: 28rpx;
    color: $mb-text-2;
    line-height: 1.6;
    white-space: pre-wrap;
  }
}

.actions {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  gap: 20rpx;
  padding: 20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 -8rpx 24rpx rgba(14, 159, 142, 0.08);

  &__delete {
    flex: 1;
  }

  &__edit {
    flex: 2;
  }
}

.delete-body {
  &__text {
    font-size: 28rpx;
    color: $mb-text-2;
    line-height: 1.6;
  }

  &__err {
    display: block;
    margin-top: 16rpx;
    font-size: 26rpx;
    color: $mb-danger;
    line-height: 1.5;
  }
}
</style>

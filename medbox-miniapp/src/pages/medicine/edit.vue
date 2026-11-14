<template>
  <view class="page">
    <MbLoading v-if="loading" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <template v-else>
      <MbCard :title="isEdit ? '编辑药品' : '新建药品'">
        <MbField
          v-model="form.name"
          label="药品名称"
          placeholder="如：阿莫西林胶囊"
          :error="nameError"
          @blur="validateName"
        />
        <view class="row">
          <MbField v-model="form.dosePerTime" class="row__item" label="默认单次剂量" placeholder="如：1" />
          <MbField v-model="form.unit" class="row__item" label="单位" placeholder="片 / 粒 / ml / 瓶" />
        </view>
        <MbField
          v-model="form.description"
          type="textarea"
          label="药品说明（AI 分析依据）"
          placeholder="照抄说明书即可，如：主要成分：阿莫西林；适应症：呼吸道感染；禁忌：对青霉素过敏者禁用；注意事项：服药期间忌酒（最多 2000 字）"
        />
        <MbField
          v-model="form.storageCond"
          type="textarea"
          label="储存条件"
          placeholder="如：遮光、密封、阴凉处保存"
        />
      </MbCard>

      <view class="save">
        <MbButton type="primary" block :loading="saving" @click="save">保存</MbButton>
      </view>
    </template>
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbField from '@/components/MbField.vue'
import MbLoading from '@/components/MbLoading.vue'
import { getMedicine, saveMedicine } from '@/api/medicine.js'
import { listDevices } from '@/api/device.js'
import { CODE } from '@/api/request.js'
import { buildMedicinePayload } from '@/utils/medicine.js'

/** 表单初始值。 */
function emptyForm() {
  return {
    medicineId: '',
    name: '',
    dosePerTime: '',
    unit: '',
    description: '',
    storageCond: '',
    ownerElderId: '',
  }
}

/** 编辑页加载失败文案按 code 细分（请求已 silent）。 */
function errorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权访问或药品不存在'
  if (err && err.code === CODE.NOT_FOUND) return '药品不存在或已删除'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '加载失败'
}

export default {
  name: 'MedicineEditPage',
  components: { MbButton, MbCard, MbEmpty, MbField, MbLoading },
  data() {
    return {
      medicineId: '',
      isEdit: false,
      loading: false,
      error: '',
      saving: false,
      form: emptyForm(),
      nameError: '',
      elders: [],
      eldersLoaded: false,
      selectedElder: '',
    }
  },
  onLoad(options) {
    this.medicineId = (options && options.medicineId) || ''
    this.isEdit = !!this.medicineId
    uni.setNavigationBarTitle({ title: this.isEdit ? '编辑药品' : '新建药品' })
    this.loadElders()
    if (this.isEdit) {
      this.load()
    }
  },
  methods: {
    async load() {
      this.loading = true
      this.error = ''
      try {
        const medicine = await getMedicine(this.medicineId, { silent: true })
        this.prefill(medicine)
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    prefill(medicine) {
      const form = emptyForm()
      form.medicineId = medicine.medicineId || this.medicineId
      form.name = medicine.name || ''
      form.dosePerTime = medicine.dosePerTime || ''
      form.unit = medicine.unit || ''
      form.description = medicine.description || ''
      form.storageCond = medicine.storageCond || ''
      this.form = form
      this.selectedElder = medicine.ownerElderId || ''
    },
    validateName() {
      this.nameError = this.form.name.trim() ? '' : '请输入药品名称'
    },
    async loadElders() {
      if (this.eldersLoaded) return
      try {
        // P1 `mp-guardian` 前没有"我监护的老人列表"接口，暂从可见设备的 elderId 归纳
        // （与 `feat/mp-device` 列表页归纳 WS 订阅主题同一思路）
        const devices = await listDevices(undefined, { silent: true })
        const elders = []
        ;(devices || []).forEach((device) => {
          if (device.elderId && elders.indexOf(device.elderId) === -1) elders.push(device.elderId)
        })
        this.elders = elders
        this.eldersLoaded = true
        // 归属不在页面展示：新建时自动归到第一位可见老人
        if (!this.isEdit && !this.selectedElder && elders.length) this.selectedElder = elders[0]
      } catch (err) {
        // 拉不到设备不阻塞表单：保存时仍会拦"暂无可归属的老人"
        this.elders = []
      }
    },
    async save() {
      this.validateName()
      if (this.nameError) return
      if (!this.selectedElder) {
        uni.showToast({ title: '暂无可归属的老人，请先登记药箱设备', icon: 'none' })
        return
      }
      if (String(this.form.description || '').length > 2000) {
        uni.showToast({ title: '药品说明最多 2000 字', icon: 'none' })
        return
      }
      const payload = buildMedicinePayload({
        ...this.form,
        ownerElderId: this.selectedElder,
      })
      this.saving = true
      try {
        await saveMedicine(payload, { loading: '保存中' })
        uni.showToast({ title: '已保存', icon: 'success' })
        setTimeout(() => uni.navigateBack(), 300)
      } catch (err) {
        // 请求层已 toast 后端 message（40001 会带首条校验错误）；仅补 40302 的明确文案
        if (err && err.code === CODE.GUARDIAN_PENDING) {
          uni.showToast({ title: '监护关系未生效，请等待老人确认', icon: 'none' })
        }
      } finally {
        this.saving = false
      }
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

.row {
  display: flex;
  gap: 20rpx;
}

/* WXSS 不支持 `.row > *` 通配符（H5 能过、小程序编译报错），用显式类名 */
.row__item {
  flex: 1;
}

.save {
  margin-top: 32rpx;
}
</style>

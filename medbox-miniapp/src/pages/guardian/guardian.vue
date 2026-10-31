<template>
  <view class="page">
    <view class="hero">
      <view class="hero__top">
        <view class="hero__avatar">{{ avatarText }}</view>
        <view class="hero__who">
          <text class="hero__name">{{ name || '未登录' }}</text>
          <text class="hero__role">{{ roleText }}</text>
        </view>
      </view>
      <text class="hero__greeting">{{ heroHint }}</text>
    </view>

    <!-- 监护人屏：发起申请 + 我的申请（本地视图） -->
    <view v-if="isGuardian" class="section">
      <MbCard title="发起申请">
        <MbField
          v-model="elderIdInput"
          label="老人用户ID"
          placeholder="请输入老人的用户ID，如 u-1001"
          :error="applyError"
          :disabled="applying"
        />
        <MbButton type="primary" block :loading="applying" :disabled="!canSubmit" @click="onApply">
          发起申请
        </MbButton>
        <text class="hint">请向老人索取用户ID（老人可在"我的"页面查看）。每位老人只需绑定一次，重复申请会提示冲突。</text>
      </MbCard>

      <MbCard title="我的申请">
        <view v-if="applications.length === 0" class="app-empty">还没有发起过监护申请</view>
        <view v-for="app in applications" :key="app.relationId" class="app">
          <view class="app__head">
            <text class="app__elder">{{ elderLabel(app) }}</text>
            <view class="app__badge"><text class="app__badge-text">待确认</text></view>
          </view>
          <text class="app__meta">申请于 {{ relationTimeText(app.createdAt) }}</text>
          <view class="app__actions">
            <MbButton class="app__btn" type="ghost" @click="onRemove(app)">移除记录</MbButton>
            <MbButton class="app__btn" type="primary" @click="onRevoke(app)">撤销申请</MbButton>
          </view>
        </view>
      </MbCard>

      <MbCard title="绑定流程">
        <view class="flow">
          <view v-for="(step, index) in flowSteps" :key="index" class="flow__step">
            <text class="flow__num">{{ index + 1 }}</text>
            <text class="flow__text">{{ step }}</text>
          </view>
        </view>
        <text class="hint hint--danger">老人确认前，访问该老人的数据会提示 40302（监护关系未生效）。</text>
      </MbCard>
    </view>

    <!-- 老人屏：待确认列表 + 确认 / 拒绝 -->
    <view v-else-if="isElder" class="section">
      <MbLoading v-if="loading && pending.length === 0" fullscreen text="加载中" />

      <MbEmpty v-else-if="loadError" :text="loadError" />

      <template v-else-if="pending.length > 0">
        <MbCard v-for="item in pending" :key="item.relationId" :title="item.guardianName || '监护申请'">
          <MbCell title="手机号" :value="item.guardianPhone || '未提供'" />
          <MbCell title="申请时间" :value="relationTimeText(item.createdAt)" />
          <view class="req-actions">
            <MbButton class="req-actions__btn" type="ghost" @click="onReject(item)">拒绝</MbButton>
            <MbButton class="req-actions__btn" type="primary" @click="onOpenAccept(item)">确认</MbButton>
          </view>
        </MbCard>
        <text class="list-hint">确认后，监护人即可查看与管理你的用药数据；之后也可随时在此解除。</text>
      </template>

      <MbEmpty v-else-if="!loading" text="暂无待确认的监护请求" />
    </view>

    <view v-else class="section">
      <MbEmpty text="当前账号角色不支持监护关系管理" />
    </view>

    <!-- 确认弹窗：确认监护请求（无角色，确认即生效） -->
    <MbModal
      v-model="acceptVisible"
      title="确认监护请求"
      confirm-text="确认"
      cancel-text="我再想想"
      :confirm-loading="accepting"
      :close-on-confirm="false"
      :mask-closable="false"
      @confirm="onConfirmAccept"
    >
      <text class="accept-tip">确认后，{{ acceptingName || '该监护人' }} 即可查看并管理你的用药数据。</text>
      <text v-if="acceptError" class="roles__error">{{ acceptError }}</text>
    </MbModal>

    <MbTabBar />
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbField from '@/components/MbField.vue'
import MbLoading from '@/components/MbLoading.vue'
import MbModal from '@/components/MbModal.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import { acceptGuardian, applyGuardian, listPendingGuardians, revokeGuardian } from '@/api/guardian.js'
import { getElderProfile } from '@/api/user.js'
import { CODE } from '@/api/request.js'
import {
  normalizeElderId,
  parsePendingList,
  relationTimeText,
} from '@/utils/guardian.js'
import { elderNameText } from '@/utils/profile.js'
import { addMyApplication, loadMyApplications, removeMyApplication } from '@/store/guardian.js'
import { getUserInfo, ROLE, roleLabel } from '@/store/user.js'

/** 列表错误文案按 code 细分（请求已 silent，页面统一自绘）。 */
function errorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权访问或账号状态异常'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '加载失败'
}

/** 申请错误文案：40301 目标不是老人 / 40901 已有关系，其余取后端 message。 */
function applyErrorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '该用户不存在或不是老人账号'
  if (err && err.code === CODE.CONFLICT) return '已存在进行中或已生效的监护关系，无需重复申请'
  return (err && err.message) || '申请失败，请稍后重试'
}

/** 确认错误文案：40901 已被处理（并发 / 老人在另一端操作过）。 */
function acceptErrorText(err) {
  if (err && err.code === CODE.CONFLICT) return '该请求已被处理，请刷新列表'
  if (err && err.code === CODE.FORBIDDEN) return '无权处理该请求'
  return (err && err.message) || '确认失败，请稍后重试'
}

export default {
  name: 'GuardianPage',
  components: { MbButton, MbCard, MbCell, MbEmpty, MbField, MbLoading, MbModal, MbTabBar },
  data() {
    return {
      role: '',
      name: '',
      // 监护人屏
      elderIdInput: '',
      applying: false,
      applyError: '',
      applications: [],
      // elderId → 老人资料响应（GET /users/{elderId}/profile）；姓名是增强信息，失败不回填
      elderProfiles: {},
      resolvingNames: false,
      // 老人屏
      loading: false,
      loadError: '',
      pending: [],
      // 确认弹窗
      acceptVisible: false,
      acceptingId: '',
      acceptingName: '',
      accepting: false,
      acceptError: '',
    }
  },
  computed: {
    avatarText() {
      return (this.name || '?').slice(0, 1)
    },
    roleText() {
      return roleLabel(this.role)
    },
    isGuardian() {
      return this.role === ROLE.GUARDIAN
    },
    isElder() {
      return this.role === ROLE.ELDER
    },
    heroHint() {
      return this.isElder ? '确认监护请求后，监护人即可帮你管理用药' : '输入老人的用户ID，即可发起监护申请'
    },
    canSubmit() {
      return !!normalizeElderId(this.elderIdInput) && !this.applying
    },
    flowSteps() {
      return [
        '输入老人的用户ID，发起监护申请',
        '老人在本页确认你的监护申请',
        '确认生效后，即可查看与管理该老人的设备、计划与记录',
      ]
    },
  },
  onShow() {
    const info = getUserInfo()
    this.role = info.role || ''
    this.name = info.name || ''
    if (this.isGuardian) {
      this.applications = loadMyApplications()
      this.resolveElderNames()
    } else if (this.isElder) {
      this.loadPending()
    }
  },
  methods: {
    relationTimeText,

    // ------------------------------------------------------------ 监护人屏

    /**
     * 按 elderId 批量解析老人姓名（`feat/mp-profile`）：申请卡把"u-1001"显示成"张桂英（u-1001）"。
     *
     * <p>**静默是硬约束**：姓名只是增强信息 —— 40302（申请中老人未确认）/ 40301（被拒 /
     * 无关系）/ 网络失败一律不回填、不 toast，页面回退 elderId 原文。只补**缺失**的 elderId
     * （本地申请通常 1-3 条，重复进页不重复请求）。
     */
    async resolveElderNames() {
      if (this.resolvingNames) return
      const missing = []
      this.applications.forEach((app) => {
        if (app.elderId && !this.elderProfiles[app.elderId] && missing.indexOf(app.elderId) === -1) {
          missing.push(app.elderId)
        }
      })
      if (missing.length === 0) return
      this.resolvingNames = true
      try {
        await Promise.all(
          missing.map((elderId) =>
            getElderProfile(elderId, { silent: true })
              .then((profile) => {
                if (profile) this.elderProfiles[elderId] = profile
              })
              .catch((err) => {
                // 只打 code 与 elderId，不打响应体（含手机号）
                console.warn('[guardian] 老人姓名解析失败', elderId, err && err.code ? err.code : err)
              })
          )
        )
      } finally {
        this.resolvingNames = false
      }
    },

    /** 申请卡标题：解析到姓名 → "张桂英（u-1001）"；未解析 / 失败 → elderId 原文。 */
    elderLabel(app) {
      const text = elderNameText(this.elderProfiles[app.elderId], app.elderId)
      return text || '未知老人'
    },

    async onApply() {
      const elderId = normalizeElderId(this.elderIdInput)
      if (!elderId) {
        this.applyError = '请输入老人用户ID'
        return
      }
      this.applying = true
      this.applyError = ''
      try {
        const relation = await applyGuardian(elderId, { silent: true, loading: '提交中' })
        // 申请响应即时入本地缓存：后端无"我发起的申请列表"接口，这是监护人侧唯一的可见来源
        this.applications = addMyApplication(relation || { relationId: '', elderId })
        // 新申请的老人姓名补解析（PENDING 阶段服务端返 40302，静默回退 elderId）
        this.resolveElderNames()
        this.elderIdInput = ''
        uni.showToast({ title: '已提交，等待老人确认', icon: 'none' })
      } catch (err) {
        this.applyError = applyErrorText(err)
      } finally {
        this.applying = false
      }
    },

    /** 撤销申请：调解除接口（服务端对 REVOKED 幂等，老人已拒绝时调用也安全）+ 清本地。 */
    onRevoke(app) {
      uni.showModal({
        title: '撤销申请',
        content: `撤销后对 ${app.elderId || '该老人'} 的监护申请立即失效。确定撤销？`,
        success: (res) => {
          if (res.confirm) this.doRevoke(app)
        },
      })
    },
    async doRevoke(app) {
      try {
        await revokeGuardian(app.relationId, { silent: true, loading: '撤销中' })
        this.applications = removeMyApplication(app.relationId)
        uni.showToast({ title: '已撤销', icon: 'none' })
      } catch (err) {
        uni.showToast({ title: (err && err.message) || '撤销失败，请稍后重试', icon: 'none' })
      }
    },

    /**
     * 移除记录：**只清本地缓存，不调接口**。
     *
     * <p>本地列表是设备本地视图：老人可能已确认（关系 ACTIVE），此时调解除接口会**真解除**
     * 监护关系。仅当用户明确想撤销申请时才走 {@link onRevoke}。
     */
    onRemove(app) {
      uni.showModal({
        title: '移除记录',
        content: '仅从本机移除这条申请记录，不会改变监护关系状态（老人可能已确认）。',
        success: (res) => {
          if (!res.confirm) return
          this.applications = removeMyApplication(app.relationId)
          uni.showToast({ title: '已移除', icon: 'none' })
        },
      })
    },

    // ------------------------------------------------------------ 老人屏

    async loadPending() {
      this.loading = true
      this.loadError = ''
      try {
        const data = await listPendingGuardians({ silent: true })
        this.pending = parsePendingList(data).list
      } catch (err) {
        this.loadError = errorText(err)
      } finally {
        this.loading = false
      }
    },

    onOpenAccept(item) {
      this.acceptingId = item.relationId
      this.acceptingName = item.guardianName || ''
      this.acceptError = ''
      this.acceptVisible = true
    },

    async onConfirmAccept() {
      if (this.accepting) return
      this.accepting = true
      this.acceptError = ''
      try {
        await acceptGuardian(this.acceptingId, { silent: true })
        this.pending = this.pending.filter((item) => item.relationId !== this.acceptingId)
        this.acceptVisible = false
        uni.showToast({ title: '已确认', icon: 'none' })
      } catch (err) {
        // close-on-confirm=false：出错留着弹窗让老人改选或重试
        this.acceptError = acceptErrorText(err)
      } finally {
        this.accepting = false
      }
    },

    onReject(item) {
      uni.showModal({
        title: '拒绝监护请求',
        content: `拒绝后 ${item.guardianName || '该监护人'} 将无法查看你的用药数据。`,
        success: (res) => {
          if (res.confirm) this.doReject(item)
        },
      })
    },
    async doReject(item) {
      try {
        await revokeGuardian(item.relationId, { silent: true, loading: '处理中' })
        this.pending = this.pending.filter((it) => it.relationId !== item.relationId)
        uni.showToast({ title: '已拒绝', icon: 'none' })
      } catch (err) {
        uni.showToast({ title: (err && err.message) || '操作失败，请稍后重试', icon: 'none' })
      }
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

.hero {
  margin: -24rpx -24rpx 24rpx;
  padding: 48rpx 40rpx;
  background: $mb-gradient;
  border-bottom-left-radius: 40rpx;
  border-bottom-right-radius: 40rpx;

  &__top {
    display: flex;
    align-items: center;
  }

  &__avatar {
    width: 96rpx;
    height: 96rpx;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.25);
    color: #fff;
    font-size: 44rpx;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__who {
    margin-left: 24rpx;
    display: flex;
    flex-direction: column;
  }

  &__name {
    font-size: 38rpx;
    font-weight: 600;
    color: #fff;
  }

  &__role {
    margin-top: 6rpx;
    font-size: 24rpx;
    color: rgba(255, 255, 255, 0.85);
  }

  &__greeting {
    display: block;
    margin-top: 28rpx;
    font-size: 26rpx;
    color: #fff;
  }
}

.hint {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: $mb-text-3;

  &--danger {
    color: $mb-danger;
  }
}

.app-empty {
  padding: 24rpx 0 8rpx;
  font-size: 26rpx;
  color: $mb-text-4;
}

.app {
  padding: 20rpx 0;
  border-bottom: 2rpx solid $mb-border;

  &:last-child {
    border-bottom: none;
    padding-bottom: 0;
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__elder {
    font-size: 30rpx;
    font-weight: 500;
    color: $mb-text;
  }

  &__badge {
    height: 40rpx;
    padding: 0 16rpx;
    border-radius: $mb-radius-pill;
    background: rgba(240, 173, 78, 0.14);
    display: inline-flex;
    align-items: center;

    &-text {
      font-size: 22rpx;
      color: $mb-warning;
    }
  }

  &__meta {
    display: block;
    margin-top: 8rpx;
    font-size: 24rpx;
    color: $mb-text-3;
  }

  &__actions {
    display: flex;
    justify-content: flex-end;
    gap: 16rpx;
    margin-top: 16rpx;
  }

  /* MbButton 无 size 变体，借父级作用域压一档（子组件根节点带父级 scope 属性，选择器可达） */
  &__btn {
    height: 64rpx;
    font-size: 26rpx;
    padding: 0 28rpx;
  }
}

.flow {
  &__step {
    display: flex;
    align-items: flex-start;
    margin-bottom: 16rpx;

    &:last-of-type {
      margin-bottom: 0;
    }
  }

  &__num {
    width: 40rpx;
    height: 40rpx;
    border-radius: 50%;
    background: $mb-primary-bg;
    color: $mb-primary;
    font-size: 24rpx;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-right: 16rpx;
    flex-shrink: 0;
  }

  &__text {
    flex: 1;
    font-size: 26rpx;
    line-height: 1.6;
    color: $mb-text-2;
  }
}

.list-hint {
  display: block;
  margin-bottom: $mb-gap;
  font-size: 24rpx;
  line-height: 1.6;
  color: $mb-text-3;
}

.req-actions {
  display: flex;
  justify-content: flex-end;
  gap: 16rpx;
  margin-top: 16rpx;

  &__btn {
    height: 64rpx;
    font-size: 26rpx;
    padding: 0 28rpx;
  }
}

.accept-tip {
  display: block;
  margin-bottom: 20rpx;
  font-size: 26rpx;
  color: $mb-text-2;
}

.roles {
  display: flex;
  gap: 16rpx;

  &__item {
    flex: 1;
    height: 72rpx;
    border-radius: $mb-radius-pill;
    background: $mb-bg;
    border: 2rpx solid $mb-border;
    display: flex;
    align-items: center;
    justify-content: center;

    &--on {
      background: $mb-primary-bg;
      border-color: $mb-primary;
    }
  }

  &__label {
    font-size: 26rpx;
    color: $mb-text-2;
  }

  &__item--on &__label {
    color: $mb-primary;
    font-weight: 500;
  }

  &__perm {
    display: block;
    margin-top: 16rpx;
    font-size: 24rpx;
    line-height: 1.6;
    color: $mb-text-3;
  }

  &__error {
    display: block;
    margin-top: 12rpx;
    font-size: 24rpx;
    color: $mb-danger;
  }
}
</style>

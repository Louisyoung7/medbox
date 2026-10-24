<template>
  <view class="page">
    <view class="profile-hero">
      <view class="profile-hero__avatar">{{ avatarText }}</view>
      <view class="profile-hero__info">
        <text class="profile-hero__name">{{ name || '未登录' }}</text>
        <text class="profile-hero__role">{{ roleText }}</text>
      </view>
    </view>

    <MbCard title="账号">
      <MbCell title="用户ID" :value="userId || '-'" />
      <MbCell title="角色" :value="roleText" />
    </MbCard>

    <!-- 开发调试（feat/mp-devtools）：真机联调时改后端 HOST，不必重新编译 -->
    <MbCard title="开发调试">
      <MbCell
        title="服务器地址与联调说明"
        label="局域网联调：改后端 HOST、真机调试流程"
        arrow
        clickable
        @click="onOpenDevtools"
      />
    </MbCard>

    <RoleGate entry="alarmSettings">
      <MbCard title="监护台">
        <text class="guard-hint">仅监护人可见：告警设置、环境阈值、为老人配置 AI Key 将在此区域。</text>
      </MbCard>
    </RoleGate>

    <view class="profile-actions">
      <MbButton type="ghost" block @click="onLogout">退出登录</MbButton>
    </view>

    <MbCard title="危险操作">
      <MbCell
        class="profile-danger__row"
        title="注销账号"
        label="注销后账号不可恢复，手机号可重新注册"
        arrow
        clickable
        @click="onOpenDelete"
      />
    </MbCard>

    <MbModal
      v-model="deleteVisible"
      title="注销账号"
      confirm-text="确认注销"
      cancel-text="我再想想"
      confirm-type="danger"
      :confirm-disabled="!password || deleting"
      :confirm-loading="deleting"
      :close-on-confirm="false"
      :mask-closable="false"
      @confirm="onConfirmDelete"
      @cancel="onCancelDelete"
    >
      <text class="delete-warn">此操作不可撤销：账号将永久停用，全部登录凭据与监护关系立即失效，业务数据（用药记录等）保留但不再可登录。</text>
      <MbField
        v-model="password"
        label="登录密码"
        type="password"
        placeholder="请输入当前账号密码"
        :error="deleteError"
        :disabled="deleting"
      />
    </MbModal>

    <MbTabBar />
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbButton from '@/components/MbButton.vue'
import MbModal from '@/components/MbModal.vue'
import MbField from '@/components/MbField.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import RoleGate from '@/components/RoleGate.vue'
import { CODE } from '@/api/request.js'
import { deleteAccount, logout } from '@/store/auth.js'
import { getUserInfo, getUserId, roleLabel } from '@/store/user.js'

export default {
  name: 'ProfilePage',
  components: { MbCard, MbCell, MbButton, MbModal, MbField, MbTabBar, RoleGate },
  data() {
    return {
      name: '',
      userId: '',
      roleText: '',
      // 注销弹窗的三态：显隐 / 输入的密码 / 提交流水（错误文案 + 是否在飞）
      deleteVisible: false,
      password: '',
      deleteError: '',
      deleting: false,
    }
  },
  onShow() {
    const info = getUserInfo()
    this.name = info.name || ''
    this.userId = info.userId || ''
    this.roleText = roleLabel(info.role)
  },
  computed: {
    avatarText() {
      return (this.name || '?').slice(0, 1)
    },
  },
  methods: {
    /**
     * 打开开发调试页（`feat/mp-devtools`）：运行时改后端 HOST + 联调说明。
     *
     * <p>不按角色限制（联调便利工具，不涉及业务数据）；页面**不在** `store/guard.js`
     * 白名单里，未登录时会被守卫拦下并回跳登录页 —— 符合"调试页要登录后才可用"的预期。
     */
    onOpenDevtools() {
      uni.navigateTo({ url: '/pages/devtools/devtools' })
    },

    onLogout() {
      uni.showModal({
        title: '退出登录',
        content: '退出后需要重新输入账号密码',
        success: (res) => {
          if (res.confirm) logout()
        },
      })
    },

    /**
     * 打开注销弹窗：每次都从干净状态开始（密码不缓存 —— 这是最后一次确认，不是记住）
     */
    onOpenDelete() {
      this.password = ''
      this.deleteError = ''
      this.deleting = false
      this.deleteVisible = true
    },

    /** 取消注销：抹掉输入的密码，别把它留在内存里。 */
    onCancelDelete() {
      this.password = ''
      this.deleteError = ''
    },

    /**
     * 提交注销：只调 `store/auth.js` 的编排，不自己拼"调接口 + 清 token"。
     *
     * <p>弹窗的关闭时机由本页掌控（`close-on-confirm=false`）：成功才关；密码错误 / 网络失败时
     * 留着让用户改密码重试。**40101 静默返回** —— 请求层已经续过期，仍失败时它自己清了凭据并
     * `reLaunch` 到登录页，这里再 toast 一次只会重复。
     */
    async onConfirmDelete() {
      if (this.deleting || !this.password) {
        return
      }
      this.deleting = true
      this.deleteError = ''
      try {
        await deleteAccount(this.password)
        this.password = ''
        this.deleteVisible = false
      } catch (err) {
        if (err && err.code === CODE.UNAUTHORIZED) {
          // 登录态已失效：请求层负责清凭据与跳登录页，页面不再提示
          this.deleteVisible = false
          return
        }
        this.deleteError = (err && err.message) || '注销失败，请稍后重试'
      } finally {
        this.deleting = false
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

.profile-hero {
  display: flex;
  align-items: center;
  padding: 40rpx;
  margin-bottom: $mb-gap;
  border-radius: $mb-radius-lg;
  background: $mb-gradient;

  &__avatar {
    width: 104rpx;
    height: 104rpx;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.25);
    color: #fff;
    font-size: 48rpx;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__info {
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
    margin-top: 8rpx;
    font-size: 26rpx;
    color: rgba(255, 255, 255, 0.85);
  }
}

.guard-hint {
  font-size: 26rpx;
  color: $mb-text-3;
  line-height: 1.6;
}

.profile-actions {
  margin-top: 12rpx;
}

/* 注销入口：整行走危险色（scoped 下要改 MbCell 内部元素得用 :deep） */
.profile-danger__row {
  :deep(.mb-cell__title) {
    color: $mb-danger;
    font-weight: 500;
  }

  :deep(.mb-cell__arrow) {
    border-top-color: rgba(229, 72, 77, 0.6);
    border-right-color: rgba(229, 72, 77, 0.6);
  }
}

.delete-warn {
  display: block;
  margin-bottom: 20rpx;
  font-size: 26rpx;
  line-height: 1.6;
  color: $mb-danger;
}
</style>

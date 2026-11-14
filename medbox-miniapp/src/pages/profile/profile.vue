<template>
  <view class="page">
    <view class="profile-hero">
      <view class="profile-hero__avatar">{{ avatarText }}</view>
      <view class="profile-hero__info">
        <text class="profile-hero__name">{{ displayName }}</text>
        <text class="profile-hero__role">{{ roleText }}</text>
      </view>
    </view>

    <MbCard title="个人资料">
      <MbCell title="用户ID" :value="userId || '-'" />
      <MbCell title="姓名" :value="fieldText(name)" />
      <MbCell title="手机号" :value="fieldText(phone)" />
      <MbCell title="用户名" :value="fieldText(username)" />
      <MbCell title="角色" :value="roleText" :label="rolePermission" />
    </MbCard>

    <!-- 绑定引导（feat/mp-profile）：仅老人可见（ENTRY_ROLES.elderBindGuide）。
         落实 02 的 3.2 口径"老人可在'我的'页面查看自己的用户ID"——监护绑定要输入老人用户ID。 -->
    <RoleGate entry="elderBindGuide">
      <MbCard title="告诉监护人">
        <text class="bind-hint">把用户ID {{ userId || '-' }} 告诉监护人，对方在「监护关系」页输入即可发起绑定；你确认后生效。</text>
        <view class="bind-action">
          <MbButton type="primary" block @click="goGuardian">去监护关系页</MbButton>
        </view>
      </MbCard>
    </RoleGate>

    <RoleGate entry="alarmSettings">
      <MbCard title="监护台">
        <text class="guard-hint">仅监护人可见：告警设置、环境阈值、为老人配置 AI Key 将在此区域。</text>
      </MbCard>
    </RoleGate>

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
import { getMe } from '@/api/auth.js'
import { CODE } from '@/api/request.js'
import { deleteAccount, logout } from '@/store/auth.js'
import { getUserInfo, roleLabel, setUserInfo } from '@/store/user.js'
import { needsProfileRefresh, profileFieldText, rolePermissionText } from '@/utils/profile.js'

export default {
  name: 'ProfilePage',
  components: { MbCard, MbCell, MbButton, MbModal, MbField, MbTabBar, RoleGate },
  data() {
    return {
      name: '',
      userId: '',
      role: '',
      phone: '',
      username: '',
      roleText: '',
      refreshing: false,
      // 注销弹窗的三态：显隐 / 输入的密码 / 提交流水（错误文案 + 是否在飞）
      deleteVisible: false,
      password: '',
      deleteError: '',
      deleting: false,
    }
  },
  computed: {
    avatarText() {
      return (this.name || this.username || '?').slice(0, 1)
    },
    /** hero 姓名三级兜底：姓名 → 用户名 → "未设置姓名"（原实现空 name 误显"未登录"）。 */
    displayName() {
      return this.name || this.username || '未设置姓名'
    },
    /** 角色权限一句说明（口径取 01 第 3 章 / 02 的 1.3 权限矩阵）。 */
    rolePermission() {
      return rolePermissionText(this.role)
    },
  },
  onShow() {
    const info = getUserInfo()
    this.applyInfo(info)
    // 登录时 getMe 失败会留下缺 name/phone 的半份缓存：缺关键字段才静默补一次
    // （TabBar 页 onShow 频繁，缓存满时零请求，见 utils/profile.needsProfileRefresh）
    if (needsProfileRefresh(info)) {
      this.refreshProfile()
    }
  },
  methods: {
    fieldText: profileFieldText,

    /** 把用户资料渲染到页面（onShow 与静默补齐成功后共用）。 */
    applyInfo(info) {
      this.name = info.name || ''
      this.userId = info.userId || ''
      this.role = info.role || ''
      this.phone = info.phone || ''
      this.username = info.username || ''
      this.roleText = roleLabel(info.role)
    },

    /**
     * 静默补齐 `/users/me`（`feat/mp-profile`）：不出遮罩不出 toast，失败只打 code。
     *
     * <p>**为什么静默**：这是背景修复（登录时拉取失败留下的半份缓存），用户并没有点
     * "刷新"，弹 toast 会把"看资料"变成"被报错"。40101 由请求层先自动续期重发一次，
     * 仍失败时它自己清了凭据并跳登录页，这里再提示只会重复。
     */
    async refreshProfile() {
      if (this.refreshing) return
      this.refreshing = true
      try {
        const me = await getMe({ silent: true })
        if (me) {
          // 合并写回：后端 non_null 会省略空字段，旧缓存值兜底避免抹掉已有数据
          setUserInfo({ ...getUserInfo(), ...me })
          this.applyInfo(getUserInfo())
        }
      } catch (err) {
        // 只打 code，不打响应体（含手机号等敏感字段）
        console.warn('[profile] 补齐用户资料失败', err && err.code ? err.code : err)
      } finally {
        this.refreshing = false
      }
    },

    /** 去监护关系页（老人端的绑定引导入口；监护人在该页是申请屏，同样可用）。 */
    goGuardian() {
      uni.navigateTo({ url: '/pages/guardian/guardian' })
    },

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

/* 绑定引导卡（feat/mp-profile）：主色浅底说明区 + 胶囊按钮 */
.bind-hint {
  display: block;
  padding: 24rpx;
  border-radius: $mb-radius-sm;
  background: $mb-primary-bg;
  font-size: 26rpx;
  line-height: 1.6;
  color: $mb-text-2;
}

.bind-action {
  margin-top: 20rpx;
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

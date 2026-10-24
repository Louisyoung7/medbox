<template>
  <div class="login-wrap">
    <el-card class="login-card" shadow="always">
      <div class="login-header">
        <div class="logo">💊</div>
        <h1 class="title">medbox 管理后台</h1>
        <p class="subtitle">测试数据清理 · 开发 / 测试专用</p>
      </div>

      <el-alert type="warning" :closable="false" show-icon class="login-warn">
        仅用于开发 / 测试库，请勿连接生产库
      </el-alert>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="账号（手机号 / 用户名）" prop="account">
          <el-input
            v-model="form.account"
            size="large"
            clearable
            placeholder="如 admin 或 13800001234"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            size="large"
            placeholder="请输入密码"
            @keyup.enter="onSubmit"
          />
        </el-form-item>

        <p v-if="errorMsg" class="login-error">{{ errorMsg }}</p>

        <el-button
          type="primary"
          size="large"
          class="login-btn"
          :loading="loading"
          @click="onSubmit"
        >
          登 录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

const formRef = ref(null)
const form = reactive({ account: '', password: '' })
const loading = ref(false)
const errorMsg = ref('')

const rules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function onSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    errorMsg.value = ''
    try {
      const data = await auth.login(form.account, form.password)
      if (data.role !== 'ADMIN') {
        // 非 ADMIN 一律拒绝进入（本工具仅 ADMIN 可用）
        errorMsg.value = '当前账号非 ADMIN，无权使用测试数据清理工具'
        auth.logout()
        return
      }
      router.replace('/test-data')
    } catch (e) {
      // 40102 已在表单内红字提示，不重复 toast
      errorMsg.value = (e && e.message) || '登录失败'
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #eef2f7 0%, #dbeafe 100%);
}
.login-card {
  width: 400px;
  padding: 8px 14px 18px;
  border: none;
  border-radius: 16px;
  box-shadow: 0 12px 40px rgba(64, 158, 255, 0.15);
  animation: floatIn 0.5s ease;
}
@keyframes floatIn {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: none; }
}
.login-header { text-align: center; margin-bottom: 10px; }
.logo { font-size: 40px; }
.title { font-size: 22px; font-weight: 600; color: #303133; margin: 8px 0 4px; }
.subtitle { font-size: 14px; color: #909399; margin: 0; }
.login-warn { margin-bottom: 18px; }
.login-btn { width: 100%; margin-top: 6px; }
.login-error { color: #f56c6c; font-size: 13px; margin: 0 0 10px; text-align: center; }
</style>

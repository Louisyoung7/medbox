<template>
  <div class="page">
    <!-- 顶部护栏横幅（09 护栏③） -->
    <el-alert class="banner" type="warning" :closable="false" show-icon>
      <template #title>
        <strong>测试数据清理 · 开发 / 测试专用，勿接生产库</strong>
      </template>
      <template #default>
        本工具会<strong>硬删</strong>用户及其全部孤儿数据，操作不可恢复。仅用于开发 / 测试库。
      </template>
    </el-alert>

    <!-- 筛选条 -->
    <el-card class="toolbar" shadow="never">
      <el-form inline @submit.prevent>
        <el-form-item label="状态">
          <el-select v-model="filters.status" style="width: 160px" @change="onSearch">
            <el-option label="测试 / 已注销" value="TEST" />
            <el-option label="已注销" value="DELETED" />
            <el-option label="活跃" value="ACTIVE" />
            <el-option label="全部" value="ALL" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input
            v-model="filters.keyword"
            placeholder="手机号 / 用户名"
            clearable
            @keyup.enter="onSearch"
            @clear="onSearch"
          />
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="filters.role"
            placeholder="全部"
            clearable
            style="width: 130px"
            @change="onSearch"
          >
            <el-option label="监护人" value="GUARDIAN" />
            <el-option label="老人" value="ELDER" />
            <el-option label="管理员" value="ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
        <el-form-item class="batch-entry">
          <el-button
            type="danger"
            :disabled="batchTargets.length === 0"
            @click="openBatch"
          >
            清理全部已注销测试用户 ({{ batchTargets.length }})
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 用户表格 -->
    <el-table :data="tableData" v-loading="loading" border stripe class="tbl">
      <el-table-column prop="userId" label="用户ID" width="120" />
      <el-table-column prop="role" label="角色" width="100">
        <template #default="{ row }">
          <el-tag :type="roleTag(row.role)">{{ roleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="姓名" />
      <el-table-column prop="phone" label="手机号">
        <template #default="{ row }">{{ row.phone || '—' }}</template>
      </el-table-column>
      <el-table-column prop="username" label="用户名">
        <template #default="{ row }">{{ row.username || '—' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="200" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-tooltip v-if="row.status === 'ACTIVE'" content="活跃账号不可清理" placement="top">
            <span><el-button size="small" type="danger" disabled>清理</el-button></span>
          </el-tooltip>
          <el-button v-else size="small" type="danger" plain @click="openSingle(row)">清理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      v-model:current-page="page"
      :page-size="size"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="fetch"
    />

    <!-- 单条二次确认（手输 userId，09 护栏②） -->
    <el-dialog v-model="singleVisible" title="确认硬删该测试用户？" width="460px">
      <el-alert type="error" :closable="false" show-icon style="margin-bottom: 12px">
        将硬删用户 <b>{{ pending?.userId }}</b> 及其全部孤儿数据，<b>不可恢复</b>。
      </el-alert>
      <p class="confirm-tip">
        请输入目标用户ID <code>{{ pending?.userId }}</code> 以确认：
      </p>
      <el-input v-model="singleInput" placeholder="输入用户ID" />
      <template #footer>
        <el-button @click="singleVisible = false">取消</el-button>
        <el-button type="danger" :disabled="!singleConfirmed" @click="confirmSingle">
          确认硬删
        </el-button>
      </template>
    </el-dialog>

    <!-- 批量二次确认（手输数量，附目标列表） -->
    <el-dialog v-model="batchVisible" title="确认批量硬删测试用户？" width="520px">
      <el-alert type="error" :closable="false" show-icon style="margin-bottom: 12px">
        将硬删 <b>{{ batchTargets.length }}</b> 个测试用户及其全部孤儿数据，<b>不可恢复</b>。
      </el-alert>
      <p class="confirm-tip">
        请输入待清理数量 <code>{{ batchTargets.length }}</code> 以确认：
      </p>
      <el-input v-model="batchInput" placeholder="输入数量" />
      <el-collapse class="id-list" style="margin-top: 10px">
        <el-collapse-item title="查看目标用户ID" name="1">
          <div class="ids">{{ batchTargets.map((r) => r.userId).join('、') }}</div>
        </el-collapse-item>
      </el-collapse>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="danger" :disabled="!batchConfirmed" @click="confirmBatch">
          确认批量硬删
        </el-button>
      </template>
    </el-dialog>

    <!-- 可选审计（09 护栏④） -->
    <el-collapse v-if="audit.length" class="audit">
      <el-collapse-item title="本次操作记录" name="audit">
        <el-table :data="audit" size="small">
          <el-table-column prop="operator" label="操作人" width="140" />
          <el-table-column prop="userId" label="目标用户ID" width="140" />
          <el-table-column prop="time" label="时间" width="200" />
          <el-table-column prop="result" label="结果" />
        </el-table>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listUsers, deleteUser } from '../api/admin'
import { useAuthStore } from '../stores/auth'
import {
  isSafeViewRow,
  isCleanable,
  confirmMatch,
  collectBatchTargets,
} from '../utils/guard'

const auth = useAuthStore()

const filters = reactive({ status: 'TEST', keyword: '', role: '' })
const page = ref(1)
const size = ref(20)
const total = ref(0)
const tableData = ref([])
const loading = ref(false)

const singleVisible = ref(false)
const singleInput = ref('')
const pending = ref(null)

const batchVisible = ref(false)
const batchInput = ref('')
const audit = ref([])

const singleConfirmed = computed(
  () => pending.value && confirmMatch(singleInput.value, pending.value.userId),
)
const batchConfirmed = computed(
  () => String(batchTargets.value.length) === batchInput.value.trim(),
)
const batchTargets = computed(() => collectBatchTargets(tableData.value))

function roleLabel(r) {
  return { GUARDIAN: '监护人', ELDER: '老人', ADMIN: '管理员' }[r] || r
}
function roleTag(r) {
  return { GUARDIAN: '', ELDER: 'warning', ADMIN: 'danger' }[r] || 'info'
}

async function fetch() {
  loading.value = true
  try {
    const q = { page: page.value, size: size.value }
    if (filters.keyword) q.keyword = filters.keyword
    if (filters.role) q.role = filters.role
    // 仅 DELETED / ACTIVE 透传给后端；TEST / ALL 取全部（TEST 再客户端收敛）
    if (filters.status === 'DELETED') q.status = 'DELETED'
    else if (filters.status === 'ACTIVE') q.status = 'ACTIVE'

    const res = await listUsers(q)
    const rows = res?.list || []
    // 护栏①：默认「测试/已注销」视图客户端过滤，只显 DELETED 或 test*/1380000*
    tableData.value = filters.status === 'TEST' ? rows.filter(isSafeViewRow) : rows
    total.value = res?.total ?? rows.length
  } catch (e) {
    ElMessage.error((e && e.message) || '加载失败')
  } finally {
    loading.value = false
  }
}

function onSearch() {
  page.value = 1
  fetch()
}
function onReset() {
  filters.status = 'TEST'
  filters.keyword = ''
  filters.role = ''
  page.value = 1
  fetch()
}

function openSingle(row) {
  pending.value = row
  singleInput.value = ''
  singleVisible.value = true
}
async function confirmSingle() {
  const target = pending.value
  if (!target || !confirmMatch(singleInput.value, target.userId)) return
  try {
    await deleteUser(target.userId)
    ElMessage.success(`已清理 ${target.userId}`)
    pushAudit(target.userId, '成功')
    singleVisible.value = false
    fetch()
  } catch (e) {
    ElMessage.error((e && e.message) || '清理失败')
    pushAudit(target.userId, `失败：${e && e.message ? e.message : '未知错误'}`)
  }
}

function openBatch() {
  batchInput.value = ''
  batchVisible.value = true
}
async function confirmBatch() {
  const targets = batchTargets.value
  if (String(targets.length) !== batchInput.value.trim()) return
  let ok = 0
  let fail = 0
  for (const row of targets) {
    try {
      await deleteUser(row.userId)
      ok++
      pushAudit(row.userId, '成功')
    } catch (e) {
      fail++
      pushAudit(row.userId, `失败：${e && e.message ? e.message : '未知错误'}`)
    }
  }
  batchVisible.value = false
  ElMessage.success(`批量清理完成：成功 ${ok} / 失败 ${fail}`)
  fetch()
}

function pushAudit(userId, result) {
  audit.value.unshift({
    operator: auth.userId || auth.role || 'admin',
    userId,
    time: new Date().toISOString(),
    result,
  })
}

onMounted(fetch)
</script>

<style scoped>
.page { padding: 16px; max-width: 1200px; margin: 0 auto; }
.banner { margin-bottom: 14px; border-radius: 8px; }
.toolbar { margin-bottom: 14px; border-radius: 10px; }
.batch-entry { margin-left: auto; }
.tbl { border-radius: 10px; overflow: hidden; }
.pager { margin-top: 12px; justify-content: flex-end; }
.confirm-tip { font-size: 14px; color: #606266; margin: 0 0 8px; }
.confirm-tip code { background: #f0f2f5; padding: 1px 6px; border-radius: 4px; color: #409eff; }
.ids { font-size: 13px; color: #606266; line-height: 1.8; word-break: break-all; }
.audit { margin-top: 16px; border-radius: 10px; }
</style>

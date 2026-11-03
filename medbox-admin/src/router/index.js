import { createRouter, createWebHistory } from 'vue-router'
import { getToken, getRole } from '../stores/token'
import { ElMessage } from 'element-plus'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  { path: '/test-data', name: 'test-data', component: () => import('../views/TestData.vue') },
  { path: '/dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue') },
  { path: '/', redirect: '/dashboard' },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

// 需要 ADMIN 角色才能进入的页面（看板与测试数据清理共用登录态）
const ADMIN_ONLY = ['/test-data', '/dashboard']

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// ADMIN 路由守卫：未登录 → /login；非 ADMIN → 拦截并提示。
router.beforeEach((to) => {
  const token = getToken()
  if (to.path === '/login') return true
  if (!token) return '/login'
  if (ADMIN_ONLY.includes(to.path) && getRole() !== 'ADMIN') {
    ElMessage.error('无权限：需要 ADMIN 角色')
    return '/login'
  }
  return true
})

export default router

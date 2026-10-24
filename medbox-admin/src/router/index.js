import { createRouter, createWebHistory } from 'vue-router'
import { getToken, getRole } from '../stores/token'
import { ElMessage } from 'element-plus'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  { path: '/test-data', name: 'test-data', component: () => import('../views/TestData.vue') },
  { path: '/', redirect: '/test-data' },
  { path: '/:pathMatch(.*)*', redirect: '/test-data' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// ADMIN 路由守卫：未登录 → /login；非 ADMIN → 拦截并提示。
router.beforeEach((to) => {
  const token = getToken()
  if (to.path === '/login') return true
  if (!token) return '/login'
  if (to.path === '/test-data' && getRole() !== 'ADMIN') {
    ElMessage.error('无权限：需要 ADMIN 角色')
    return '/login'
  }
  return true
})

export default router

import { defineStore } from 'pinia'
import { login as apiLogin } from '../api/auth'
import { getToken, getRole, setToken, setRole, setUserId, clearAuth } from './token'

// Pinia auth store：登录态编排；token/role/userId 持久化到 localStorage（见 token.js）。
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getToken(),
    role: getRole(),
    userId: localStorage.getItem('medbox_admin_userId') || '',
    loggedIn: !!getToken(),
  }),
  actions: {
    async login(account, password) {
      const data = await apiLogin(account, password)
      setToken(data.token)
      setRole(data.role)
      setUserId(data.userId)
      this.token = data.token
      this.role = data.role
      this.userId = data.userId || ''
      this.loggedIn = true
      return data
    },
    logout() {
      clearAuth()
      this.token = ''
      this.role = ''
      this.userId = ''
      this.loggedIn = false
    },
  },
})

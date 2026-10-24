// token / role 的唯一读写入口（localStorage 持久化）。避免在多个模块里各存一份凭据。

const TOKEN_KEY = 'medbox_admin_token'
const ROLE_KEY = 'medbox_admin_role'
const USERID_KEY = 'medbox_admin_userId'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(t) {
  if (t) localStorage.setItem(TOKEN_KEY, t)
  else localStorage.removeItem(TOKEN_KEY)
}

export function getRole() {
  return localStorage.getItem(ROLE_KEY) || ''
}

export function setRole(r) {
  if (r) localStorage.setItem(ROLE_KEY, r)
  else localStorage.removeItem(ROLE_KEY)
}

export function setUserId(id) {
  if (id) localStorage.setItem(USERID_KEY, id)
  else localStorage.removeItem(USERID_KEY)
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ROLE_KEY)
  localStorage.removeItem(USERID_KEY)
}

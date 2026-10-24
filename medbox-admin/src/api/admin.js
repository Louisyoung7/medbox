import request from './request'

// 列出用户（medbox-spec/02 第 10.1）：GET /admin/users
// params: { status?, keyword?, role?, page?, size? }，返回 PageResult<AdminUserRow>（含 list / total）
export function listUsers(params) {
  return request.get('/admin/users', { params })
}

// 清理（硬删 + 级联，medbox-spec/02 第 10.2）：DELETE /admin/users/{userId}
// ACTIVE → 40902；不存在 → 40401；非 ADMIN → 40301
export function deleteUser(userId) {
  return request.delete(`/admin/users/${encodeURIComponent(userId)}`)
}

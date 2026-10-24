import request from './request'

// 复用 medbox-spec/02 第 3 章 POST /auth/login：账号可为手机号或用户名。
// 返回 data：{ token, refreshToken, userId, role, expiresIn }
export function login(account, password) {
  return request.post('/auth/login', { account, password })
}

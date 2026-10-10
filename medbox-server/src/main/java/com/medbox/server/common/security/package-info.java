/**
 * 认证内核：JWT 签发 / 解析、密码哈希、当前登录用户上下文。
 *
 * <p>与 Web 层解耦（{@code JwtProvider} 不依赖 Servlet API），后续 {@code feat/backend-ws} 的
 * 握手鉴权、{@code feat/backend-authz} 的权限判定都复用这里。
 */
package com.medbox.server.common.security;

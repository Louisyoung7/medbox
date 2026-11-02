package com.medbox.server.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析：优先 {@code X-Forwarded-For} / {@code X-Real-IP}（反向代理场景），
 * 回退到 {@code request.getRemoteAddr()}。
 *
 * <p>局域网直连部署下 {@code remoteAddr} 即各设备/客户端的内网 IP，作为限流 key 合理；
 * 经网关/NAT 出网时多个客户端会共享出口 IP——本期局域网部署可接受（与文档 01 的部署口径一致）。
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (isNotBlank(forwarded)) {
            // 首个即真实客户端 IP（后续为各级代理）
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (isNotBlank(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}

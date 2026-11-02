package com.medbox.server.common.ratelimit;

import com.medbox.server.common.security.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.util.AntPathMatcher;

/**
 * 限流策略：三维度的匹配规则（请求方法 + 路径）、限流 key 来源与默认阈值。
 *
 * <p>配置（{@code medbox.ratelimit.*}）可覆盖 limit / window-seconds；本枚举里的
 * {@code defaultLimit} / {@code defaultWindowSeconds} 是兜底默认（见 02 的 1.4）。
 *
 * <p>维度与 key 选择（feat/ai-limit 决策）：
 * <ul>
 *   <li>登录：免登录态（AuthInterceptor 已排除），只能靠 IP 防爆破；</li>
 *   <li>命令下发：按 deviceId 防刷爆单台物理设备；</li>
 *   <li>AI 洞察：按调用者 userId（来自 AuthContext）防单用户刷 BYOK 配额。</li>
 * </ul>
 */
public enum RateLimitPolicy {

    LOGIN("auth-login", "POST", List.of("/api/v1/auth/login"),
            KeySource.IP, 10, 60),
    AI_INSIGHT("ai-insight", "POST",
            List.of("/api/v1/ai/insight", "/api/v1/ai/insight/stream"),
            KeySource.USER, 10, 60),
    DEVICE_COMMAND("device-command", "POST",
            List.of("/api/v1/devices/{deviceId}/commands"),
            KeySource.DEVICE, 20, 60);

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final String dimension;
    private final String method;
    private final List<String> patterns;
    private final KeySource keySource;
    private final int defaultLimit;
    private final int defaultWindowSeconds;

    RateLimitPolicy(String dimension, String method, List<String> patterns,
                    KeySource keySource, int defaultLimit, int defaultWindowSeconds) {
        this.dimension = dimension;
        this.method = method;
        this.patterns = patterns;
        this.keySource = keySource;
        this.defaultLimit = defaultLimit;
        this.defaultWindowSeconds = defaultWindowSeconds;
    }

    public String dimension() {
        return dimension;
    }

    public int defaultLimit() {
        return defaultLimit;
    }

    public int defaultWindowSeconds() {
        return defaultWindowSeconds;
    }

    /** 该请求是否命中本策略（方法 + 路径都匹配）。 */
    public boolean matches(HttpServletRequest request) {
        if (!method.equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = resolvePath(request);
        return patterns.stream().anyMatch(p -> MATCHER.match(p, path));
    }

    /** 取本策略的限流 key。 */
    public String resolveKey(HttpServletRequest request) {
        return switch (keySource) {
            case IP -> ClientIpResolver.resolve(request);
            case USER -> Optional.ofNullable(AuthContext.currentUserId()).orElse("anonymous");
            case DEVICE -> resolveDeviceId(request);
        };
    }

    /**
     * 取用于匹配的路径：优先 {@code servletPath}（真实容器由 DispatcherServlet 填好），
     * 否则回退到"requestURI 去掉 context-path"（MockMvc / MockHttpServletRequest 场景）。
     */
    private static String resolvePath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }

    @SuppressWarnings("unchecked")
    private static String resolveDeviceId(HttpServletRequest request) {
        Map<String, String> vars =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String id = vars == null ? null : vars.get("deviceId");
        return id != null ? id : "unknown";
    }

    /** 限流 key 来源。 */
    public enum KeySource {
        IP, USER, DEVICE
    }
}

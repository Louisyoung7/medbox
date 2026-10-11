package com.medbox.server.ws;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.JwtPayload;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.dto.R;
import com.medbox.server.service.AccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.ObjectMapper;

/**
 * WebSocket 握手鉴权（见文档 04 第 1 章）：{@code 校验 JWT → 解析 topics → 按归属鉴权}。
 *
 * <p><b>为什么不用 {@code AuthInterceptor}</b>：它只拦 MVC 的 {@code /api/v1/**}，握手请求根本不进
 * DispatcherServlet 的处理器链。而 <b>权限判定本身仍复用 {@link AccessService}</b> —— 与 REST 同一口径
 * （见 02 的 1.3）：老人只能订阅自己，监护人只能订阅 ACTIVE 监护关系下的老人；PENDING / REVOKED /
 * 无关系一律按越权处理，避免泄漏"曾有关系"。
 *
 * <p><b>拒绝时写 HTTP 状态 + 统一响应体</b>：握手失败不走 {@code GlobalExceptionHandler}（它在 MVC 链内），
 * 因此这里自己写 —— 状态码与 {@link ErrorCode#httpStatus()} 对齐（40101→401、40301→403、40001→400），
 * 响应体仍用 {@link R} 的 {@code code/message/traceId/timestamp} 结构，客户端解析逻辑不用分叉。
 *
 * <p><b>必须清 {@code AuthContext}</b>：握手运行在容器线程上，不清会把上一个用户的身份带进下一次握手
 * （与 {@code AuthInterceptor.afterCompletion} 同一条纪律）。
 */
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    /** 鉴权通过后写入 handshake attributes 的 key。 */
    public static final String ATTR_SUBSCRIPTION = WsHandshakeInterceptor.class.getName() + ".subscription";

    private static final Logger log = LoggerFactory.getLogger(WsHandshakeInterceptor.class);

    private static final String PARAM_TOKEN = "token";
    private static final String PARAM_TOPICS = "topics";
    private static final String PARAM_LAST_EVENT_ID = "lastEventId";

    private static final String JSON_CONTENT_TYPE = "application/json;charset=UTF-8";

    private final JwtProvider jwtProvider;
    private final AccessService accessService;
    private final ObjectMapper objectMapper;

    public WsHandshakeInterceptor(JwtProvider jwtProvider, AccessService accessService, ObjectMapper objectMapper) {
        this.jwtProvider = jwtProvider;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        try {
            HttpServletRequest servletRequest = servletRequestOf(request);
            if (servletRequest == null) {
                reject(response, ErrorCode.BAD_REQUEST, "非 Servlet 容器的握手请求");
                return false;
            }

            // ① 登录：JWT 无效 / 过期 / 类型不对 → 40101
            String token = servletRequest.getParameter(PARAM_TOKEN);
            if (token == null || token.isBlank()) {
                reject(response, ErrorCode.UNAUTHORIZED, "缺少 token 参数");
                return false;
            }
            JwtPayload payload;
            try {
                payload = jwtProvider.parse(token, TokenType.ACCESS);
            } catch (BizException ex) {
                reject(response, ErrorCode.UNAUTHORIZED, ex.getMessage());
                return false;
            }
            AuthContext.set(new AuthContext.CurrentUser(payload.userId(), payload.role()));

            // ② 主题语法：只认 elder:{elderId}，device: 明确拒绝并给原因
            List<String> topics = WsTopic.split(servletRequest.getParameter(PARAM_TOPICS));
            if (topics.isEmpty()) {
                reject(response, ErrorCode.BAD_REQUEST, "缺少 topics 参数，形如 topics=elder:e-1001");
                return false;
            }
            for (String topic : topics) {
                String reason = WsTopic.rejectionReason(topic);
                if (reason != null) {
                    reject(response, ErrorCode.BAD_REQUEST, reason);
                    return false;
                }
            }

            // ③ 归属鉴权：订阅的主题必须落在"我可见的老人"集合内（一次查询覆盖所有主题，避免 N+1）
            Set<String> visibleElderIds = accessService.visibleElderIds();
            for (String topic : topics) {
                Optional<String> elderId = WsTopic.elderIdOf(topic);
                if (elderId.isEmpty() || !visibleElderIds.contains(elderId.get())) {
                    log.warn("WS 握手越权 userId={} topic={}", payload.userId(), topic);
                    reject(response, ErrorCode.FORBIDDEN, "无权限订阅该主题");
                    return false;
                }
            }

            long lastEventSeq = WsEventId.parse(servletRequest.getParameter(PARAM_LAST_EVENT_ID))
                    .orElse(WsEventId.NO_SEQ);
            attributes.put(ATTR_SUBSCRIPTION, new WsSubscription(payload.userId(), topics, lastEventSeq));
            return true;
        } catch (BizException ex) {
            // AccessService 抛的 40301 / 40302 等：与 REST 同码，只是出口不同
            reject(response, ex.errorCode(), ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            log.error("WS 握手鉴权异常：{}", ex.getMessage());
            reject(response, ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message());
            return false;
        } finally {
            AuthContext.clear();
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无资源可清理；attributes 里的 subscription 随连接一起被 handler 消费
    }

    private void reject(ServerHttpResponse response, ErrorCode errorCode, String message) {
        HttpStatus status = errorCode.httpStatus();
        response.setStatusCode(status);
        if (!(response instanceof ServletServerHttpResponse servletResponse)) {
            return;
        }
        HttpServletResponse httpResponse = servletResponse.getServletResponse();
        httpResponse.setStatus(status.value());
        httpResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        httpResponse.setContentType(JSON_CONTENT_TYPE);
        try {
            httpResponse.getWriter().write(objectMapper.writeValueAsString(R.fail(errorCode, message)));
            httpResponse.flushBuffer();
        } catch (IOException ex) {
            log.warn("写 WS 握手失败响应出错：{}", ex.getMessage());
        }
    }

    private static HttpServletRequest servletRequestOf(ServerHttpRequest request) {
        return request instanceof ServletServerHttpRequest servletRequest
                ? servletRequest.getServletRequest()
                : null;
    }
}

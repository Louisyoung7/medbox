package com.medbox.server.common.web;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.ratelimit.RateLimitPolicy;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.config.RateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitInterceptorTest {

    private RateLimitProperties props(int limit) {
        RateLimitProperties p = new RateLimitProperties();
        p.setEnabled(true);
        p.getLogin().setLimit(limit);
        p.getAiInsight().setLimit(limit);
        p.getDeviceCommand().setLimit(limit);
        return p;
    }

    private RateLimitInterceptor interceptor(int limit) {
        return new RateLimitInterceptor(props(limit));
    }

    private MockHttpServletRequest req(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    @Test
    void 登录按IP限流_超限抛429() {
        var it = interceptor(2);
        MockHttpServletRequest request = req("POST", "/api/v1/auth/login");
        request.setRemoteAddr("9.9.9.9");
        MockHttpServletResponse res = new MockHttpServletResponse();
        it.preHandle(request, res, null);
        it.preHandle(request, res, null);
        BizException ex = assertThrows(BizException.class, () -> it.preHandle(request, res, null));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, ex.errorCode());
    }

    @Test
    void 不同IP独立计数() {
        var it = interceptor(2);
        MockHttpServletRequest a = req("POST", "/api/v1/auth/login");
        a.setRemoteAddr("1.1.1.1");
        MockHttpServletRequest b = req("POST", "/api/v1/auth/login");
        b.setRemoteAddr("2.2.2.2");
        MockHttpServletResponse res = new MockHttpServletResponse();
        it.preHandle(a, res, null);
        it.preHandle(a, res, null);
        it.preHandle(b, res, null); // b 仍可用，不抛
    }

    @Test
    void 命令按deviceId限流_从路径变量取key() {
        var it = interceptor(2);
        MockHttpServletRequest request = req("POST", "/api/v1/devices/BOXA-01/commands");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                java.util.Map.of("deviceId", "BOXA-01"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        it.preHandle(request, res, null);
        it.preHandle(request, res, null);
        BizException ex = assertThrows(BizException.class, () -> it.preHandle(request, res, null));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, ex.errorCode());
    }

    @Test
    void 洞察按userId限流_取自AuthContext() {
        var it = interceptor(2);
        AuthContext.set(new AuthContext.CurrentUser("u-2001", "GUARDIAN"));
        MockHttpServletRequest request = req("POST", "/api/v1/ai/insight");
        MockHttpServletResponse res = new MockHttpServletResponse();
        it.preHandle(request, res, null);
        it.preHandle(request, res, null);
        BizException ex = assertThrows(BizException.class, () -> it.preHandle(request, res, null));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, ex.errorCode());
    }

    @Test
    void 非限流路径直接放行() {
        var it = interceptor(0);
        MockHttpServletRequest request = req("GET", "/api/v1/users/me");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(it.preHandle(request, res, null));
    }
}

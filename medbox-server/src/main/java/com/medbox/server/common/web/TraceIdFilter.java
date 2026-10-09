package com.medbox.server.common.web;

import com.medbox.server.common.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求入口生成 / 透传 traceId（见文档 02 的 1.1）。
 *
 * <p>优先取客户端的 {@value TraceContext#REQUEST_ID_HEADER}（同时是写操作幂等头），
 * 缺失或不合法时自动生成；写入 MDC（日志用 {@code %X{traceId}} 输出）并回写
 * {@value TraceContext#TRACE_ID_HEADER} 响应头。
 *
 * <p>只做幂等头的读取，**去重逻辑不在本分支**（属 {@code feat/backend-auth}）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceContext.sanitize(request.getHeader(TraceContext.REQUEST_ID_HEADER));
        if (traceId == null) {
            traceId = TraceContext.newTraceId();
        }
        TraceContext.set(traceId);
        try {
            response.setHeader(TraceContext.TRACE_ID_HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}

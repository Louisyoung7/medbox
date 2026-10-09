package com.medbox.server.common;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * traceId 上下文：一次请求的链路标识，贯穿日志与统一响应（见文档 02 的 1.1）。
 *
 * <p>由 {@code TraceIdFilter} 在请求入口写入 MDC，响应体 {@code R.traceId} 与响应头
 * {@value #TRACE_ID_HEADER} 都取这个值，排障时可用响应里的 traceId 直接 grep 日志。
 */
public final class TraceContext {

    /** MDC 中的 key，日志 pattern 用 {@code %X{traceId}} 输出。 */
    public static final String MDC_KEY = "traceId";

    /** 客户端可自带 traceId（写操作幂等头也复用它，见文档 02 的 1）。 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 回写给客户端的响应头，便于网关 / 小程序侧记录。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final int MAX_LENGTH = 64;

    private TraceContext() {
    }

    /** 生成一个新的 traceId（32 位十六进制，无连字符）。 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 清洗外部传入的 traceId：只保留 {@code [A-Za-z0-9_-]} 且长度不超过 {@value #MAX_LENGTH}，
     * 不合法返回 {@code null}（由调用方改用自动生成），防止日志注入与超长头。
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                return null;
            }
        }
        return value;
    }

    /**
     * 当前请求的 traceId；过滤器未生效时（如单元测试直调）临时生成一个，保证响应里该字段不为空。
     */
    public static String currentTraceId() {
        String value = MDC.get(MDC_KEY);
        return (value == null || value.isBlank()) ? newTraceId() : value;
    }

    public static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    /** 请求结束必须清理，否则线程池复用会把上一个请求的 traceId 带进下一次。 */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}

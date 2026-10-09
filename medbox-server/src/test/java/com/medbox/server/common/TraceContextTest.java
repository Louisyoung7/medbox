package com.medbox.server.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TraceContextTest {

    @Test
    @DisplayName("生成的 traceId 为 32 位十六进制")
    void newTraceId() {
        assertThat(TraceContext.newTraceId()).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("外部传入的 traceId 只放行安全字符，防止日志注入")
    void sanitize() {
        assertThat(TraceContext.sanitize("req-123456")).isEqualTo("req-123456");
        assertThat(TraceContext.sanitize("  req-123456  ")).isEqualTo("req-123456");
        assertThat(TraceContext.sanitize("bad\nvalue")).isNull();
        assertThat(TraceContext.sanitize("")).isNull();
        assertThat(TraceContext.sanitize(null)).isNull();
        assertThat(TraceContext.sanitize("x".repeat(65))).isNull();
    }

    @Test
    @DisplayName("MDC 里没有时临时生成，保证响应 traceId 不为空")
    void currentTraceIdFallback() {
        TraceContext.clear();
        assertThat(TraceContext.currentTraceId()).matches("[0-9a-f]{32}");
    }
}

package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.config.CaptureProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CaptureFileTokenService} 单测：5 分钟 fileToken 的签发 / 过期 / 篡改（见文档 02 的 4.1）。
 */
@DisplayName("CaptureFileTokenService：fileToken 签发与校验")
class CaptureFileTokenServiceTest {

    private static final String SECRET = "file-secret";
    private static final Instant FIXED = Instant.parse("2026-10-04T00:00:00Z");

    private CaptureFileTokenService service;

    @BeforeEach
    void setUp() {
        CaptureProperties props = new CaptureProperties();
        props.setFileTokenSecret(SECRET);
        props.setFileTokenTtlMinutes(5);
        service = new CaptureFileTokenService(props, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("签发后校验可回吐 captureId")
    void 签发并校验() {
        String token = service.issue("cap-1");
        assertEquals("cap-1", service.verify(token));
    }

    @Test
    @DisplayName("超过 5 分钟有效期 → 40101")
    void 过期401() {
        String token = service.issue("cap-1");
        CaptureProperties p = new CaptureProperties();
        p.setFileTokenSecret(SECRET);
        p.setFileTokenTtlMinutes(5);
        CaptureFileTokenService later = new CaptureFileTokenService(
                p, Clock.fixed(FIXED.plus(10, ChronoUnit.MINUTES), ZoneOffset.UTC));
        assertThrows(BizException.class, () -> later.verify(token));
    }

    @Test
    @DisplayName("签名被篡改 → 40101")
    void 篡改401() {
        String token = service.issue("cap-1");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        assertThrows(BizException.class, () -> service.verify(tampered));
    }

    @Test
    @DisplayName("格式非法（缺段）→ 40001")
    void 格式非法400() {
        assertThrows(BizException.class, () -> service.verify("onlyonepart"));
    }
}

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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DeviceCaptureAuth} 单测：HMAC 签名 + ±5 分钟时间窗（见文档 02 的 4.1）。
 */
@DisplayName("DeviceCaptureAuth：抓拍上传设备鉴权")
class DeviceCaptureAuthTest {

    private static final String SECRET = "shared-secret";
    private static final Instant FIXED = Instant.parse("2026-10-04T00:00:00Z");
    private static final long BASE_TS = FIXED.getEpochSecond();

    private DeviceCaptureAuth auth;

    @BeforeEach
    void setUp() {
        CaptureProperties props = new CaptureProperties();
        props.setUploadSecret(SECRET);
        auth = new DeviceCaptureAuth(props, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private String sign(String deviceId, long ts) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(
                mac.doFinal((deviceId + ts).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("签名正确且时间戳在 ±5 分钟窗口内 → 通过并返回 deviceId")
    void 合法请求通过() throws Exception {
        long ts = BASE_TS;
        String sign = sign("CAM01", ts);
        assertEquals("CAM01", auth.verify("CAM01", sign, String.valueOf(ts)));
    }

    @Test
    @DisplayName("边界：正好 5 分钟（300 秒）仍通过")
    void 边界5分钟通过() throws Exception {
        long ts = BASE_TS - 300;
        assertEquals("CAM01", auth.verify("CAM01", sign("CAM01", ts), String.valueOf(ts)));
    }

    @Test
    @DisplayName("签名错误 → 40101")
    void 签名错误401() throws Exception {
        long ts = BASE_TS;
        assertThrows(BizException.class, () -> auth.verify("CAM01", "deadbeef", String.valueOf(ts)));
    }

    @Test
    @DisplayName("时间戳超出 ±5 分钟窗口 → 40101（防重放）")
    void 时间窗外401() throws Exception {
        long ts = BASE_TS - 400; // 超窗
        assertThrows(BizException.class, () -> auth.verify("CAM01", sign("CAM01", ts), String.valueOf(ts)));
    }

    @Test
    @DisplayName("缺少签名 / 设备ID → 40001")
    void 缺字段400() {
        assertThrows(BizException.class, () -> auth.verify("CAM01", null, String.valueOf(BASE_TS)));
        assertThrows(BizException.class, () -> auth.verify(null, "x", "1"));
    }

    @Test
    @DisplayName("时间戳非法（非数字）→ 40101")
    void 时间戳非法401() {
        assertThrows(BizException.class, () -> auth.verify("CAM01", "x", "not-a-number"));
    }
}

package com.medbox.server.service;

import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.config.CaptureProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 抓拍上传的设备侧鉴权（见文档 02 的 4.1）。
 *
 * <p>设备走 HTTP 直传，不走用户 JWT。固件与后端共享一把密钥（{@code medbox.capture.upload-secret}），
 * 上传时带 {@code X-Device-Id} / {@code X-Device-Sign} / {@code X-Timestamp} 三个 Header，其中
 * {@code sign = HMAC_SHA256(secret, deviceId + timestamp)}。后端校验两件事：
 * <ol>
 *   <li>签名正确（伪造或密钥不符 → 40101）；</li>
 *   <li>时间戳落在 ±5 分钟窗口内（防重放 → 40101）。</li>
 * </ol>
 *
 * <p>不做"只校验 {@code X-Device-Id} 不校验签名"的退化方案（见文档 02 的 4.1）。
 */
@Component
public class DeviceCaptureAuth {

    /** 时间窗 ±5 分钟 = 300 秒。 */
    static final long TIME_WINDOW_SECONDS = 300;

    private final CaptureProperties captureProperties;
    private final Clock clock;

    @Autowired
    public DeviceCaptureAuth(CaptureProperties captureProperties) {
        this(captureProperties, Clock.systemUTC());
    }

    /** 测试用：注入固定时钟。 */
    DeviceCaptureAuth(CaptureProperties captureProperties, Clock clock) {
        this.captureProperties = captureProperties;
        this.clock = clock;
    }

    /**
     * 校验设备上传请求的签名与时间窗。
     *
     * @param deviceId 路径里的摄像头子设备 ID（调用方需先确认与 {@code X-Device-Id} 一致）
     * @param sign     Header {@code X-Device-Sign}
     * @param timestampHeader Header {@code X-Timestamp}（Unix 秒）
     * @return 校验通过返回 {@code deviceId}；失败抛 {@link BizException}(40101)
     */
    public String verify(String deviceId, String sign, String timestampHeader) {
        BizAssert.notBlank(deviceId, "deviceId 缺失");
        BizAssert.notBlank(sign, "X-Device-Sign 缺失");
        BizAssert.notBlank(timestampHeader, "X-Timestamp 缺失");

        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "X-Timestamp 非法");
        }

        long nowSeconds = clock.instant().getEpochSecond();
        long diff = Math.abs(nowSeconds - ts);
        BizAssert.unauthorizedIf(diff > TIME_WINDOW_SECONDS,
                "时间戳超出 ±5 分钟窗口，疑似重放");

        String expected = hmac(deviceId, timestampHeader);
        BizAssert.unauthorizedIf(!constantTimeEquals(expected, sign),
                "设备签名校验失败");
        return deviceId;
    }

    /** 计算 {@code HMAC_SHA256(secret, deviceId + timestamp)}，十六进制小写返回。 */
    String hmac(String deviceId, String timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    captureProperties.getUploadSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((deviceId + timestamp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "HMAC 计算失败");
        }
    }

    /** 常量时间比较，避免时序侧信道。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < ab.length; i++) {
            result |= ab[i] ^ bb[i];
        }
        return result == 0;
    }
}

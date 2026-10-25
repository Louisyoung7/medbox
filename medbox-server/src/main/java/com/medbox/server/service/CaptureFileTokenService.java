package com.medbox.server.service;

import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.config.CaptureProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 抓拍文件读取用的短期令牌（fileToken，见文档 02 的 4.1）。
 *
 * <p>小程序 {@code <image src>} 无法携带自定义 Header，故图片地址用 {@code /captures/{id}/file?token=...}
 * 拼接一个后端签发的短期令牌（默认 5 分钟），避免把长期 JWT 暴露在 URL / 日志里。
 *
 * <p>令牌结构（base64url 两段用 {@code .} 连接）：
 * <pre>{@code base64url(payload) + "." + base64url(HMAC(fileTokenSecret, payload))}</pre>
 * 其中 {@code payload = captureId + "|" + 过期秒}。校验时验签名 + 未过期，回吐 {@code captureId}。
 *
 * <p>注意：令牌本身只证明"曾有权读取"，文件端点仍会二次走 {@code AccessService.require(READ_CAPTURE)}
 * 复核监护关系（令牌泄漏也能兜底）。
 */
@Service
public class CaptureFileTokenService {

    private final CaptureProperties captureProperties;
    private final Clock clock;

    @Autowired
    public CaptureFileTokenService(CaptureProperties captureProperties) {
        this(captureProperties, Clock.systemUTC());
    }

    /** 测试用：注入固定时钟。 */
    CaptureFileTokenService(CaptureProperties captureProperties, Clock clock) {
        this.captureProperties = captureProperties;
        this.clock = clock;
    }

    /** 签发一个绑定 {@code captureId} 的短期令牌。 */
    public String issue(String captureId) {
        BizAssert.notBlank(captureId, "captureId 缺失");
        long expiresAt = clock.instant().getEpochSecond()
                + (long) captureProperties.getFileTokenTtlMinutes() * 60L;
        String payload = captureId + "|" + expiresAt;
        String signature = hmac(payload);
        return base64url(payload) + "." + base64url(signature);
    }

    /**
     * 校验令牌并返回其中的 {@code captureId}。
     *
     * @throws BizException 40101 签名无效或已过期；40001 格式非法
     */
    public String verify(String token) {
        BizAssert.notBlank(token, "fileToken 缺失");
        String[] parts = token.split("\\.");
        BizAssert.badRequestIf(parts.length != 2, "fileToken 格式非法");

        String payload = base64urlDecode(parts[0]);
        String signature = base64urlDecode(parts[1]);
        BizAssert.unauthorizedIf(!constantTimeEquals(hmac(payload), signature), "fileToken 签名无效");

        int sep = payload.indexOf('|');
        BizAssert.badRequestIf(sep < 0, "fileToken 载荷非法");
        long expiresAt = Long.parseLong(payload.substring(sep + 1));
        BizAssert.unauthorizedIf(clock.instant().getEpochSecond() > expiresAt, "fileToken 已过期");

        return payload.substring(0, sep);
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    captureProperties.getFileTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "fileToken 签名失败");
        }
    }

    private static String base64url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64urlDecode(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

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

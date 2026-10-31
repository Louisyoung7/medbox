package com.medbox.server.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medbox.server.config.AiInsightProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * API Key 加解密与脱敏的单元测试（{@code feat/ai-insight}，见文档 05 §2.1）。
 *
 * <p>纯 JVM、不启容器：AES-GCM 的正确性由用例直接验证（往返一致 / 篡改即失败）。
 */
class ApiKeyCipherTest {

    private static final String AES_KEY = "medbox-local-dev-llm-key-change-me-32b"; // ≥32 字符
    private static final String PLAIN = "sk-test-1234567890abcdef";

    private final ApiKeyCipher cipher = new ApiKeyCipher(props(AES_KEY));

    private static AiInsightProperties props(String aesKey) {
        AiInsightProperties p = new AiInsightProperties();
        p.setAesKey(aesKey);
        return p;
    }

    @Test
    @DisplayName("加密 → 解密往返一致，密文带 v1: 版本前缀且不含明文")
    void 加解密往返一致() {
        String encrypted = cipher.encrypt(PLAIN);

        assertTrue(encrypted.startsWith("v1:"));
        assertTrue(!encrypted.contains(PLAIN));
        assertEquals(PLAIN, cipher.decrypt(encrypted));
    }

    @Test
    @DisplayName("同一明文加密两次，密文不同（IV 每次随机）")
    void 每次加密密文不同() {
        assertNotEquals(cipher.encrypt(PLAIN), cipher.encrypt(PLAIN));
    }

    @Test
    @DisplayName("明文为 null / 空白 → 加密返回 null（表示未配置）")
    void 明文空白返回null() {
        assertNull(cipher.encrypt(null));
        assertNull(cipher.encrypt("   "));
    }

    @Test
    @DisplayName("AES 口令不足 32 字符 → 构造即抛 IllegalArgumentException（启动期暴露配置错误）")
    void 短密钥构造失败() {
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyCipher(props("too-short")));
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyCipher(props(null)));
    }

    @Test
    @DisplayName("密文版本前缀不认识 → 解密抛 IllegalStateException")
    void 版本前缀不认识() {
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("v9:AAAA"));
    }

    @Test
    @DisplayName("密文被篡改 → GCM 校验失败，解密抛 IllegalStateException（不泄露半解状态）")
    void 篡改密文解密失败() {
        String encrypted = cipher.encrypt(PLAIN);
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    @DisplayName("密钥换一把 → 旧密文解不开（换 Key 即全部失效，与 05 口径一致）")
    void 换密钥旧密文不可解() {
        String encrypted = cipher.encrypt(PLAIN);
        ApiKeyCipher other = new ApiKeyCipher(props("another-key-also-32-chars-long-xx"));

        assertThrows(IllegalStateException.class, () -> other.decrypt(encrypted));
    }

    @Test
    @DisplayName("脱敏：前 2 + **** + 后 4（sk****abcd 形态）")
    void 脱敏回显() {
        assertEquals("sk****cdef", ApiKeyCipher.mask("sk12abcdef"));
        assertEquals("sk****abcd", ApiKeyCipher.mask("sk-xxxxx-abcd"));
    }

    @Test
    @DisplayName("脱敏：长度 ≤8 全遮；null / 空白 → null")
    void 脱敏边界() {
        assertEquals("****", ApiKeyCipher.mask("12345678"));
        assertEquals("****", ApiKeyCipher.mask("abc"));
        assertNull(ApiKeyCipher.mask(null));
        assertNull(ApiKeyCipher.mask("  "));
    }
}

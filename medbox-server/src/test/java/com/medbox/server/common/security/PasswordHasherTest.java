package com.medbox.server.common.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 密码哈希：不明文落库、可比对不可还原（不启容器、不连库）。 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void 哈希不等于明文且是_BCrypt_格式() {
        String hash = hasher.hash("123456");

        assertNotEquals("123456", hash);
        assertTrue(hash.startsWith("$2"), "BCrypt 哈希应以 $2a$ / $2b$ 开头");
    }

    @Test
    void 同一明文两次哈希不同_但都能比对通过() {
        String first = hasher.hash("123456");
        String second = hasher.hash("123456");

        assertNotEquals(first, second, "BCrypt 每次加盐不同");
        assertTrue(hasher.matches("123456", first));
        assertTrue(hasher.matches("123456", second));
        assertFalse(hasher.matches("654321", first));
    }

    @Test
    void 陪跑哈希恒不通过_用于账号不存在时对齐耗时() {
        assertFalse(hasher.matchesDummy("123456"));
    }
}

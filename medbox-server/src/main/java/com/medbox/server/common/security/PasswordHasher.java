package com.medbox.server.common.security;

import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码哈希：BCrypt 加盐单向哈希（见文档 01 附录《凭据的加密存储》—— **只需比对的用哈希**）。
 *
 * <p>库里只有 {@code user.password_hash}，永不出现明文；登录只用 {@link #matches} 比对，无法还原。
 */
@Component
public class PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * "账号不存在"时用的陪跑哈希：与真实哈希同构，用来做一次等价耗时的 BCrypt 计算，
     * 避免"账号不存在"比"密码错误"返回得更快而被用来枚举已注册手机号。
     */
    private final String dummyHash = new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());

    /** 生成哈希（注册用）。 */
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** 比对明文与库里的哈希。 */
    public boolean matches(String rawPassword, String storedHash) {
        return encoder.matches(rawPassword, storedHash);
    }

    /** 账号不存在时调用：白算一次，只为耗时对齐，结果恒为 false。 */
    public boolean matchesDummy(String rawPassword) {
        return encoder.matches(rawPassword, dummyHash);
    }
}

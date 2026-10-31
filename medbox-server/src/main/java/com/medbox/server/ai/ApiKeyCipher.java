package com.medbox.server.ai;

import com.medbox.server.config.AiInsightProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyCipher{
    private static final String PREFIX="v1:";
    private static final int IV_LEN=12;
    private static final int TAG_BITS=128;

    private final SecretKey key;
    private final SecureRandom random =new SecureRandom();


    public ApiKeyCipher(AiInsightProperties props){
        String raw =props.getAesKey();
        if(raw==null||raw.length()<32){
            throw new IllegalArgumentException("medbox.ai.insight.aes-key 必须 ≥32 字符（设 MEDBOX_LLM_AES_KEY）");
        }

        try{
            byte[] digest =MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            this.key =new SecretKeySpec(digest,"AES");
        }catch(GeneralSecurityException ex){
            throw new IllegalStateException("无法创建 AES 密钥",ex);
        }
    }
/** 加密；明文空白返回 null（表示"未配置"）。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("API Key 加密失败", ex);
        }
    }
/** 解密；密文空 / 版本不认识 / 解密失败都抛 IllegalStateException（由调用方转 50310）。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            throw new IllegalStateException("API Key 未配置");
        }
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalStateException("API Key 密文版本不支持");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("API Key 解密失败（密钥可能已更换）", ex);
        }
    }

/** 回显脱敏：前 2 + `****` + 后 4；长度 ≤8 全遮。 */
  public static String mask(String plain){
    if(plain==null||plain.isBlank()){
        return null;
    }
    if(plain.length()<=8){
        return "****";
    }
    return plain.substring(0,2)+"****"+plain.substring(plain.length()-4);
  }


}
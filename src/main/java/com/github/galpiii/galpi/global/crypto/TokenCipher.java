package com.github.galpiii.galpi.global.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class TokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<Integer, SecretKey> keysByVersion;
    private final int currentVersion;

    public TokenCipher(TokenEncryptionProperties properties) {
        this.currentVersion = properties.currentVersion();
        this.keysByVersion = new HashMap<>();
        properties.keys().forEach((version, encoded) -> {
            if (encoded != null && !encoded.isBlank()) {
                keysByVersion.put(version, toSecretKey(version, encoded));
            }
        });

        if (!keysByVersion.containsKey(currentVersion)) {
            throw new IllegalStateException(
                    "galpi.crypto.token.current-version=" + currentVersion + "에 해당하는 키가 없습니다.");
        }
    }

    public int currentVersion() {
        return currentVersion;
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);

        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(currentVersion), new GCMParameterSpec(TAG_BITS, iv));
            ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new TokenCipherException("토큰 암호화에 실패했습니다.");
        }

        return Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
    }

    public String decrypt(String encoded, int version) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new TokenCipherException("저장된 토큰의 인코딩이 올바르지 않습니다.");
        }
        if (raw.length <= IV_BYTES) {
            throw new TokenCipherException("저장된 토큰의 길이가 올바르지 않습니다.");
        }

        ByteBuffer buffer = ByteBuffer.wrap(raw);
        byte[] iv = new byte[IV_BYTES];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(version), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            log.warn("[Crypto] 토큰 복호화 실패. tokenVersion={} cause={}", version, e.getClass().getSimpleName());
            throw new TokenCipherException("토큰 복호화에 실패했습니다.");
        }
    }

    private SecretKey key(int version) {
        SecretKey key = keysByVersion.get(version);
        if (key == null) {
            throw new TokenCipherException("토큰 암호화 키 버전 " + version + "을 찾을 수 없습니다.");
        }
        return key;
    }

    private static SecretKey toSecretKey(int version, String encoded) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "토큰 암호화 키 버전 " + version + "이 Base64가 아닙니다.");
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "토큰 암호화 키 버전 " + version + "은 Base64로 인코딩된 "
                            + KEY_BYTES + "바이트여야 합니다. 현재 " + key.length + "바이트");
        }
        return new SecretKeySpec(key, ALGORITHM);
    }
}

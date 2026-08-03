package com.github.galpiii.galpi.domain.github.store;

import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenCipherException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class GithubUserTokenCache {

    private static final String KEY_PREFIX = "github:user-token:";
    private static final char VERSION_SEPARATOR = ':';

    private final StringRedisTemplate redisTemplate;
    private final TokenCipher tokenCipher;

    public void put(Long userId, String accessToken, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        String encoded = tokenCipher.currentVersion() + String.valueOf(VERSION_SEPARATOR)
                + tokenCipher.encrypt(accessToken);
        redisTemplate.opsForValue().set(key(userId), encoded, ttl);
    }

    public Optional<String> find(Long userId) {
        String encoded = redisTemplate.opsForValue().get(key(userId));
        if (encoded == null) {
            return Optional.empty();
        }

        int separator = encoded.indexOf(VERSION_SEPARATOR);
        if (separator <= 0) {
            log.warn("[GitHub] 캐시된 토큰 형식이 올바르지 않아 버린다 userId={}", userId);
            evict(userId);
            return Optional.empty();
        }

        try {
            int version = Integer.parseInt(encoded.substring(0, separator));
            return Optional.of(tokenCipher.decrypt(encoded.substring(separator + 1), version));
        } catch (NumberFormatException | TokenCipherException e) {
            log.warn("[GitHub] 캐시된 토큰을 복호화하지 못해 버린다 userId={} cause={}",
                    userId, e.getClass().getSimpleName());
            evict(userId);
            return Optional.empty();
        }
    }

    public void evict(Long userId) {
        redisTemplate.delete(key(userId));
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}

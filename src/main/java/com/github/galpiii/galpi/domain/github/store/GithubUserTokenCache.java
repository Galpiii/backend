package com.github.galpiii.galpi.domain.github.store;

import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenCipherException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * GitHub user access token의 조회 캐시.
 * <p>
 * DB와 마찬가지로 암호문만 보관한다. Redis는 DB보다 접근 통제가 느슨한 경우가 많고
 * (RDB 스냅샷·복제·MONITOR 등으로 값이 새어 나간다), TTL이 세션 수명만큼 길어 노출 창도 넓다.
 * 저장 형식은 {@code {키버전}:{암호문}}이다. 키 버전을 함께 두어야 키를 로테이션한 뒤에도
 * 캐시에 남아 있는 이전 버전 항목을 복호화할 수 있다.
 */
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

    /**
     * 복호화에 실패하면 캐시 미스로 처리하고 항목을 버린다. 호출부가 DB에서 다시 읽어 복구한다.
     */
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

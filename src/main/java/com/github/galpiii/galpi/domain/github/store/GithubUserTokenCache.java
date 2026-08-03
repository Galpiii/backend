package com.github.galpiii.galpi.domain.github.store;

import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenCipherException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * GitHub user access token의 읽기 캐시.
 *
 * <p>이건 캐시일 뿐이고 정본은 DB에 있다. 그래서 조회·적재는 Redis가 죽어도 실패로 번지지
 * 않게 삼킨다. 캐시가 인증 경로 전체의 단일 장애점이 되면 안 된다.
 *
 * <p>다만 {@link #evict(Long)}는 삼키지 않는다. 연결 해제나 토큰 교체에서 캐시를 비우지
 * 못했다면 이미 무효가 된 토큰이 TTL이 끝날 때까지 계속 제공될 수 있다. 이건 가용성이 아니라
 * 정확성 문제라서, 조용히 넘기는 대신 호출부가 실패를 알고 재시도하게 둔다.
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
        try {
            redisTemplate.opsForValue().set(key(userId), encoded, ttl);
        } catch (DataAccessException e) {
            log.warn("[GitHub] 토큰 캐시 적재 실패. 캐시 없이 계속한다 userId={} cause={}",
                    userId, e.getClass().getSimpleName());
            discardStaleEntry(userId);
        }
    }

    /**
     * 새 값을 못 썼다면 옛 값이 남아 있어서는 안 된다.
     *
     * <p>쓰기만 실패하는 상태가 실제로 있다 — maxmemory 초과에 noeviction이면 SET은 거부되고
     * GET은 정상이다. 이때 무효화하지 않으면 방금 교체한 토큰 대신 폐기된 옛 토큰이 TTL이
     * 끝날 때까지 계속 제공된다. 캐시 장애 시 DB로 폴백한다는 약속은 옛 값이 남지 않을 때만
     * 성립한다.
     */
    private void discardStaleEntry(Long userId) {
        try {
            evict(userId);
        } catch (DataAccessException e) {
            // 읽기도 같이 죽은 전면 장애라면 스테일을 읽을 일도 없다. 그 경우가 아니라면 위험하다.
            log.error("[GitHub] 캐시 적재도 무효화도 실패했다. 옛 토큰이 남아 있을 수 있다 userId={} cause={}",
                    userId, e.getClass().getSimpleName());
        }
    }

    public Optional<String> find(Long userId) {
        String encoded;
        try {
            encoded = redisTemplate.opsForValue().get(key(userId));
        } catch (DataAccessException e) {
            log.warn("[GitHub] 토큰 캐시 조회 실패. DB로 폴백한다 userId={} cause={}",
                    userId, e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (encoded == null) {
            return Optional.empty();
        }

        int separator = encoded.indexOf(VERSION_SEPARATOR);
        if (separator <= 0) {
            log.warn("[GitHub] 캐시된 토큰 형식이 올바르지 않아 버린다 userId={}", userId);
            evictQuietly(userId);
            return Optional.empty();
        }

        try {
            int version = Integer.parseInt(encoded.substring(0, separator));
            return Optional.of(tokenCipher.decrypt(encoded.substring(separator + 1), version));
        } catch (NumberFormatException | TokenCipherException e) {
            log.warn("[GitHub] 캐시된 토큰을 복호화하지 못해 버린다 userId={} cause={}",
                    userId, e.getClass().getSimpleName());
            evictQuietly(userId);
            return Optional.empty();
        }
    }

    /**
     * 캐시를 비운다. 실패하면 예외가 그대로 올라간다 — 무효가 된 토큰이 캐시에 남는 것을
     * 성공으로 보고할 수는 없다.
     */
    public void evict(Long userId) {
        redisTemplate.delete(key(userId));
    }

    /**
     * 못 쓰는 캐시 항목을 치우는 정리 작업. 실패해도 조회 결과에는 영향이 없다.
     */
    private void evictQuietly(Long userId) {
        try {
            evict(userId);
        } catch (DataAccessException e) {
            log.warn("[GitHub] 손상된 캐시 항목 제거 실패 userId={} cause={}",
                    userId, e.getClass().getSimpleName());
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}

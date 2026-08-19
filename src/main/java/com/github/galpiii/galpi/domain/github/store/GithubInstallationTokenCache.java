package com.github.galpiii.galpi.domain.github.store;

import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenCipherException;
import com.github.galpiii.galpi.global.util.Hashes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Installation access token 캐시.
 *
 * <p>이 토큰은 DB에 저장하지 않는다. 수명이 한 시간이라 원본을 보관할 이유가 없고, 보관하면
 * 유출 표면만 넓어진다. Redis에도 평문으로 두지 않고 user token 캐시와 같은 방식으로
 * 암호화해 넣는다 — 같은 Redis에 사는 값인데 한쪽만 평문일 이유가 없다.
 *
 * <p>키의 {@code hash}는 저장소 id 집합에서 나온다. 토큰의 유효 범위가 그 집합이므로, 범위가
 * 다르면 다른 키여야 한다. 순서만 다른 같은 집합이 다른 키가 되지 않도록 정렬 후 해시한다.
 *
 * <p>캐시가 죽어도 발급을 다시 하면 되므로 조회·적재 실패는 삼킨다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class GithubInstallationTokenCache {

    /** 토큰 수명은 1시간이다. 만료 직전 값을 집어 쓰지 않도록 5분 일찍 버린다. */
    public static final Duration TTL = Duration.ofMinutes(55);

    private static final String KEY_PREFIX = "github:installation-token:";
    private static final String ID_DELIMITER = ",";
    private static final char VERSION_SEPARATOR = ':';

    private final StringRedisTemplate redisTemplate;
    private final TokenCipher tokenCipher;

    public Optional<String> find(Long installationId, Collection<Long> repositoryIds) {
        String encoded;
        try {
            encoded = redisTemplate.opsForValue().get(key(installationId, repositoryIds));
        } catch (DataAccessException e) {
            log.warn("[GitHub] installation token 캐시 조회 실패. 새로 발급한다 cause={}",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
        if (encoded == null) {
            return Optional.empty();
        }

        int separator = encoded.indexOf(VERSION_SEPARATOR);
        if (separator <= 0) {
            return Optional.empty();
        }
        try {
            int version = Integer.parseInt(encoded.substring(0, separator));
            return Optional.of(tokenCipher.decrypt(encoded.substring(separator + 1), version));
        } catch (NumberFormatException | TokenCipherException e) {
            log.warn("[GitHub] 캐시된 installation token을 복호화하지 못해 버린다 cause={}",
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void put(Long installationId, Collection<Long> repositoryIds, String token,
                    Duration ttl) {
        Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? TTL : ttl;
        String encoded = tokenCipher.currentVersion() + String.valueOf(VERSION_SEPARATOR)
                + tokenCipher.encrypt(token);
        try {
            redisTemplate.opsForValue()
                    .set(key(installationId, repositoryIds), encoded, effective);
        } catch (DataAccessException e) {
            // 못 넣으면 다음 요청이 다시 발급한다. 발급 자체는 실패가 아니다.
            log.warn("[GitHub] installation token 캐시 적재 실패 cause={}",
                    e.getClass().getSimpleName());
        }
    }

    /** 발급받은 토큰이 곧바로 거부됐을 때처럼, 캐시에 둬서는 안 되는 값을 치운다. */
    public void evict(Long installationId, Collection<Long> repositoryIds) {
        try {
            redisTemplate.delete(key(installationId, repositoryIds));
        } catch (DataAccessException e) {
            log.warn("[GitHub] installation token 캐시 무효화 실패 cause={}",
                    e.getClass().getSimpleName());
        }
    }

    String key(Long installationId, Collection<Long> repositoryIds) {
        return KEY_PREFIX + installationId + ":" + scopeHash(repositoryIds);
    }

    /** 오름차순 정렬 → 구분자 결합 → SHA-256. 순서가 달라도 같은 키가 되어야 한다. */
    static String scopeHash(Collection<Long> repositoryIds) {
        String joined = repositoryIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .reduce((left, right) -> left + ID_DELIMITER + right)
                .orElse("");
        return Hashes.sha256Hex(joined);
    }

    /** 정렬된 범위 목록. 발급 요청과 캐시 키가 같은 집합을 보게 하려고 한곳에서 만든다. */
    public static List<Long> normalizeScope(Collection<Long> repositoryIds) {
        return repositoryIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }
}

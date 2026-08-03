package com.github.galpiii.galpi.domain.auth.store;

import com.github.galpiii.galpi.global.util.Hashes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";
    private static final String USER_INDEX_PREFIX = "auth:refresh-index:";
    private static final String REVOKED_PREFIX = "auth:refresh-revoked:";
    private static final String CONSUMED_PREFIX = "auth:refresh-consumed:";

    private static final Duration REVOKED_MARKER_TTL = Duration.ofHours(1);

    private static final Duration CONSUMED_MARKER_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;

    public void save(String refreshToken, Long userId, Duration ttl) {
        String hash = Hashes.sha256Hex(refreshToken);
        redisTemplate.opsForValue().set(KEY_PREFIX + hash, String.valueOf(userId), ttl);
        redisTemplate.opsForSet().add(userIndexKey(userId), hash);
        redisTemplate.expire(userIndexKey(userId), ttl);
    }

    public Optional<Long> consume(String refreshToken) {
        String hash = Hashes.sha256Hex(refreshToken);
        String userId = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + hash);
        if (userId == null) {
            return Optional.empty();
        }
        redisTemplate.opsForSet().remove(userIndexKey(Long.valueOf(userId)), hash);
        redisTemplate.opsForValue().set(CONSUMED_PREFIX + hash, "1", CONSUMED_MARKER_TTL);
        return Optional.of(Long.valueOf(userId));
    }

    public boolean wasRecentlyConsumed(String refreshToken) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(CONSUMED_PREFIX + Hashes.sha256Hex(refreshToken)));
    }

    public void revoke(String refreshToken) {
        String hash = Hashes.sha256Hex(refreshToken);
        String userId = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + hash);
        if (userId != null) {
            redisTemplate.opsForSet().remove(userIndexKey(Long.valueOf(userId)), hash);
        }
        markRevoked(hash);
    }

    public boolean wasRevoked(String refreshToken) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(REVOKED_PREFIX + Hashes.sha256Hex(refreshToken)));
    }

    public int revokeAll(Long userId) {
        Set<String> hashes = redisTemplate.opsForSet().members(userIndexKey(userId));
        redisTemplate.delete(userIndexKey(userId));

        if (hashes == null || hashes.isEmpty()) {
            return 0;
        }

        redisTemplate.delete(hashes.stream()
                .map(hash -> KEY_PREFIX + hash)
                .collect(Collectors.toSet()));
        hashes.forEach(this::markRevoked);

        return hashes.size();
    }

    private void markRevoked(String hash) {
        redisTemplate.opsForValue().set(REVOKED_PREFIX + hash, "1", REVOKED_MARKER_TTL);
    }

    private String userIndexKey(Long userId) {
        return USER_INDEX_PREFIX + userId;
    }
}

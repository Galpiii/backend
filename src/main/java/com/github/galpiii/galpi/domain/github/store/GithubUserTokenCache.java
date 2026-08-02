package com.github.galpiii.galpi.domain.github.store;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class GithubUserTokenCache {

    private static final String KEY_PREFIX = "github:user-token:";

    private final StringRedisTemplate redisTemplate;

    public void put(Long userId, String accessToken, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, accessToken, ttl);
    }

    public Optional<String> find(Long userId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(KEY_PREFIX + userId));
    }

    public void evict(Long userId) {
        redisTemplate.delete(KEY_PREFIX + userId);
    }
}

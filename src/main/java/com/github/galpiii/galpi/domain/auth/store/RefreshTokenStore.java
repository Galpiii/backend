package com.github.galpiii.galpi.domain.auth.store;

import com.github.galpiii.galpi.global.util.Hashes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";

    private final StringRedisTemplate redisTemplate;

    public void save(String refreshToken, Long userId, Duration ttl) {
        redisTemplate.opsForValue().set(key(refreshToken), String.valueOf(userId), ttl);
    }
    
    public Optional<Long> consume(String refreshToken) {
        String userId = redisTemplate.opsForValue().getAndDelete(key(refreshToken));
        return Optional.ofNullable(userId).map(Long::valueOf);
    }

    public void revoke(String refreshToken) {
        redisTemplate.delete(key(refreshToken));
    }

    private String key(String refreshToken) {
        return KEY_PREFIX + Hashes.sha256Hex(refreshToken);
    }
}

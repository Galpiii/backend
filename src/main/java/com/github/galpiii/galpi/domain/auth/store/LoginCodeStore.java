package com.github.galpiii.galpi.domain.auth.store;

import com.github.galpiii.galpi.global.util.Hashes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class LoginCodeStore {

    private static final String KEY_PREFIX = "auth:login-code:";
    private static final int CODE_BYTES = 32;

    private final StringRedisTemplate redisTemplate;

    public String issue(Long userId, Duration ttl) {
        String code = Hashes.randomUrlSafe(CODE_BYTES);
        redisTemplate.opsForValue().set(key(code), String.valueOf(userId), ttl);
        return code;
    }

    public Optional<Long> consume(String code) {
        String userId = redisTemplate.opsForValue().getAndDelete(key(code));
        return Optional.ofNullable(userId).map(Long::valueOf);
    }

    private String key(String code) {
        return KEY_PREFIX + Hashes.sha256Hex(code);
    }
}

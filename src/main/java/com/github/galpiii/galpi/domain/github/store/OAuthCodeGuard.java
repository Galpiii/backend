package com.github.galpiii.galpi.domain.github.store;

import com.github.galpiii.galpi.global.util.Hashes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Repository
@RequiredArgsConstructor
public class OAuthCodeGuard {

    private static final String KEY_PREFIX = "oauth:code:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public boolean markUsed(String code) {
        Boolean firstUse = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + Hashes.sha256Hex(code), "1", TTL);
        return Boolean.TRUE.equals(firstUse);
    }
}

package com.github.galpiii.galpi.domain.github.store;

import com.github.galpiii.galpi.global.util.Hashes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OAuthStateStore {

    private static final String KEY_PREFIX = "oauth:state:";
    private static final int STATE_BYTES = 32;

    public static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    public String issue(String returnPath) {
        String state = Hashes.randomUrlSafe(STATE_BYTES);
        redisTemplate.opsForValue().set(KEY_PREFIX + state, returnPath == null ? "" : returnPath, TTL);
        return state;
    }

    public Optional<String> consume(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + state));
    }
}

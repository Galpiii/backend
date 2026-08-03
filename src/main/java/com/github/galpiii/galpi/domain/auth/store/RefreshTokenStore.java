package com.github.galpiii.galpi.domain.auth.store;

import com.github.galpiii.galpi.global.util.Hashes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Refresh 토큰 저장소. 토큰 원문은 어디에도 남기지 않고 SHA-256 해시만 키로 쓴다.
 * <p>
 * 사용자별 세션 인덱스를 함께 유지해, 재사용이 감지되면 그 사용자의 모든 세션을 한 번에 폐기한다.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";
    private static final String USER_INDEX_PREFIX = "auth:refresh-index:";
    private static final String REVOKED_PREFIX = "auth:refresh-revoked:";

    /**
     * 의도적으로 폐기된 토큰임을 기억해 두는 기간. 이 흔적이 없으면 "이미 소비된 토큰이 또 왔다"를
     * 탈취로 오판해, 로그아웃 직후 재시도하는 탭 하나 때문에 다른 기기까지 로그아웃된다.
     */
    private static final Duration REVOKED_MARKER_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    public void save(String refreshToken, Long userId, Duration ttl) {
        String hash = Hashes.sha256Hex(refreshToken);
        redisTemplate.opsForValue().set(KEY_PREFIX + hash, String.valueOf(userId), ttl);
        redisTemplate.opsForSet().add(userIndexKey(userId), hash);
        // 인덱스는 가장 늦게 만료되는 세션까지 살아 있어야 한다.
        redisTemplate.expire(userIndexKey(userId), ttl);
    }

    public Optional<Long> consume(String refreshToken) {
        String hash = Hashes.sha256Hex(refreshToken);
        String userId = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + hash);
        if (userId == null) {
            return Optional.empty();
        }
        redisTemplate.opsForSet().remove(userIndexKey(Long.valueOf(userId)), hash);
        return Optional.of(Long.valueOf(userId));
    }

    /**
     * 로그아웃 등 의도된 폐기. 재사용 탐지가 이 토큰을 탈취로 오판하지 않도록 흔적을 남긴다.
     */
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

    /**
     * 해당 사용자의 모든 Refresh 토큰을 폐기한다. 폐기한 토큰도 흔적으로 남겨서, 뒤이어 들어오는
     * 다른 기기의 정상 갱신 요청이 또 한 번 전체 폐기를 일으키지 않게 한다.
     *
     * @return 폐기한 세션 수
     */
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

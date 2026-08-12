package com.github.galpiii.galpi.domain.auth.store;

import com.github.galpiii.galpi.global.util.Hashes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Refresh 토큰의 저장·회전·폐기를 담당한다.
 *
 * <p>토큰 키·사용자 인덱스·재사용 흔적은 한 덩어리로 움직여야 한다. 여러 명령으로 나누면
 * 그 사이에 들어온 동시 요청이 "토큰도 없고 흔적도 없는" 중간 상태를 보고 탈취로 오판하거나,
 * 폐기 도중에 발급된 토큰이 인덱스에서 누락돼 다시는 일괄 폐기되지 않는 고아 세션이 된다.
 * 그래서 복합 연산은 모두 단일 Lua 스크립트로 실행한다.
 *
 * <p>사용자 인덱스처럼 조회 결과에 따라 키가 정해지는 곳은 스크립트 안에서 접두사와 값을
 * 이어 붙인다. 단일 노드 Redis를 전제로 한 것이며, 클러스터로 옮기면 해시 슬롯이 갈라지므로
 * 키 설계를 다시 봐야 한다.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh:";
    private static final String USER_INDEX_PREFIX = "auth:refresh-index:";
    private static final String REVOKED_PREFIX = "auth:refresh-revoked:";
    private static final String CONSUMED_PREFIX = "auth:refresh-consumed:";
    private static final String REVOKE_BARRIER_PREFIX = "auth:refresh-barrier:";

    private static final Duration REVOKED_MARKER_TTL = Duration.ofHours(1);

    private static final Duration CONSUMED_MARKER_TTL = Duration.ofSeconds(30);

    /**
     * 일괄 폐기 직전에 토큰을 소비한 회전이 폐기 직후에 새 토큰을 심어 살아남는 것을 막는 창.
     * 회전 경로만 이 장벽을 존중한다. 새 로그인은 막지 않는다.
     */
    private static final Duration REVOKE_BARRIER_TTL = Duration.ofSeconds(10);

    /** KEYS 1=토큰 2=사용자 인덱스 3=폐기 장벽 · ARGV 1=userId 2=ttl초 3=해시 4=장벽 존중 여부 */
    private static final RedisScript<Long> SAVE = RedisScript.of("""
            if ARGV[4] == '1' and redis.call('EXISTS', KEYS[3]) == 1 then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            redis.call('SADD', KEYS[2], ARGV[3])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    /** KEYS 1=토큰 2=소비 흔적 · ARGV 1=인덱스 접두사 2=해시 3=흔적 ttl초 */
    private static final RedisScript<String> CONSUME = RedisScript.of("""
            local userId = redis.call('GET', KEYS[1])
            if not userId then
              return false
            end
            redis.call('DEL', KEYS[1])
            redis.call('SREM', ARGV[1] .. userId, ARGV[2])
            redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
            return userId
            """, String.class);

    /** KEYS 1=토큰 2=폐기 흔적 · ARGV 1=인덱스 접두사 2=해시 3=흔적 ttl초 */
    private static final RedisScript<Long> REVOKE = RedisScript.of("""
            local userId = redis.call('GET', KEYS[1])
            if userId then
              redis.call('DEL', KEYS[1])
              redis.call('SREM', ARGV[1] .. userId, ARGV[2])
            end
            redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
            return 1
            """, Long.class);

    /** KEYS 1=사용자 인덱스 2=폐기 장벽 · ARGV 1=토큰 접두사 2=흔적 접두사 3=흔적 ttl초 4=장벽 ttl초 */
    private static final RedisScript<Long> REVOKE_ALL = RedisScript.of("""
            local hashes = redis.call('SMEMBERS', KEYS[1])
            redis.call('DEL', KEYS[1])
            redis.call('SET', KEYS[2], '1', 'EX', ARGV[4])
            local deleted = 0
            for i = 1, #hashes do
              deleted = deleted + redis.call('DEL', ARGV[1] .. hashes[i])
              redis.call('SET', ARGV[2] .. hashes[i], '1', 'EX', ARGV[3])
            end
            return deleted
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 새 로그인으로 세션을 연다. 직전에 일괄 폐기가 있었더라도 새 로그인은 막지 않는다.
     */
    public void save(String refreshToken, Long userId, Duration ttl) {
        store(refreshToken, userId, ttl, false);
    }

    /**
     * 회전으로 새 Refresh 토큰을 발급한다.
     *
     * @return 폐기 장벽에 걸려 발급하지 못했으면 false. 회전의 근거가 된 세션이 방금 일괄
     * 폐기됐다는 뜻이므로 호출부는 재로그인을 요구해야 한다.
     */
    public boolean saveRotated(String refreshToken, Long userId, Duration ttl) {
        return store(refreshToken, userId, ttl, true);
    }

    private boolean store(String refreshToken, Long userId, Duration ttl, boolean honorBarrier) {
        String hash = Hashes.sha256Hex(refreshToken);
        Long stored = redisTemplate.execute(
                SAVE,
                List.of(KEY_PREFIX + hash, userIndexKey(userId), barrierKey(userId)),
                String.valueOf(userId),
                String.valueOf(ttl.toSeconds()),
                hash,
                honorBarrier ? "1" : "0");
        return stored != null && stored == 1L;
    }

    /**
     * 토큰을 소비하고 소비 흔적을 남긴다. 둘이 한 번에 일어나므로 동시에 들어온 두 번째
     * 요청은 토큰을 놓치더라도 반드시 흔적을 보게 된다.
     */
    public Optional<Long> consume(String refreshToken) {
        String hash = Hashes.sha256Hex(refreshToken);
        String userId = redisTemplate.execute(
                CONSUME,
                List.of(KEY_PREFIX + hash, CONSUMED_PREFIX + hash),
                USER_INDEX_PREFIX,
                hash,
                String.valueOf(CONSUMED_MARKER_TTL.toSeconds()));
        return Optional.ofNullable(userId).map(Long::valueOf);
    }

    /**
     * 토큰을 소비하지 않고 소유자만 읽는다.
     *
     * <p>GitHub 설치 콜백처럼 state가 유실될 수 있는 브라우저 리다이렉트에서 "이 브라우저가
     * 누구인가"만 알면 되는 곳에 쓴다. 여기서 회전시키면 사용자가 보던 탭의 Refresh 토큰이
     * 갈려 나가 멀쩡한 세션이 끊긴다.
     *
     * <p>이것만으로 인증하지 마라. 재사용·폐기 흔적을 보지 않으므로 탈취된 토큰도 통과한다.
     * 부수 효과가 없는 동작을 사용자에게 귀속시킬 때만 쓴다.
     */
    public Optional<Long> peek(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }
        String userId = redisTemplate.opsForValue()
                .get(KEY_PREFIX + Hashes.sha256Hex(refreshToken));
        return Optional.ofNullable(userId).map(Long::valueOf);
    }

    public boolean wasRecentlyConsumed(String refreshToken) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(CONSUMED_PREFIX + Hashes.sha256Hex(refreshToken)));
    }

    public void revoke(String refreshToken) {
        String hash = Hashes.sha256Hex(refreshToken);
        redisTemplate.execute(
                REVOKE,
                List.of(KEY_PREFIX + hash, REVOKED_PREFIX + hash),
                USER_INDEX_PREFIX,
                hash,
                String.valueOf(REVOKED_MARKER_TTL.toSeconds()));
    }

    public boolean wasRevoked(String refreshToken) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(REVOKED_PREFIX + Hashes.sha256Hex(refreshToken)));
    }

    /**
     * 사용자의 모든 세션을 폐기하고, 진행 중이던 회전이 새 토큰을 심지 못하도록 장벽을 세운다.
     */
    public int revokeAll(Long userId) {
        Long deleted = redisTemplate.execute(
                REVOKE_ALL,
                List.of(userIndexKey(userId), barrierKey(userId)),
                KEY_PREFIX,
                REVOKED_PREFIX,
                String.valueOf(REVOKED_MARKER_TTL.toSeconds()),
                String.valueOf(REVOKE_BARRIER_TTL.toSeconds()));
        return deleted == null ? 0 : deleted.intValue();
    }

    private String userIndexKey(Long userId) {
        return USER_INDEX_PREFIX + userId;
    }

    private String barrierKey(Long userId) {
        return REVOKE_BARRIER_PREFIX + userId;
    }
}

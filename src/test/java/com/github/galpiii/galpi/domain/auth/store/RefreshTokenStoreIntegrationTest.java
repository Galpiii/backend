package com.github.galpiii.galpi.domain.auth.store;

import com.github.galpiii.galpi.global.util.Hashes;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Redis에 붙여 검증한다.
 *
 * <p>이 클래스가 mock 기반 테스트를 대체한다. 여기서 확인해야 할 성질은 "어떤 명령을 불렀는가"가
 * 아니라 "동시에 들어와도 상태가 깨지지 않는가"인데, 후자는 mock으로는 원리상 표현할 수 없다.
 */
@DisplayName("RefreshTokenStore — 실제 Redis")
class RefreshTokenStoreIntegrationTest extends IntegrationTestSupport {

    private static final long USER_ID = 7L;
    private static final Duration TTL = Duration.ofDays(14);

    @Autowired
    private RefreshTokenStore store;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private String token;

    private static String randomToken() {
        return "refresh." + Hashes.randomUrlSafe(24);
    }

    private String tokenKey(String refreshToken) {
        return "auth:refresh:" + Hashes.sha256Hex(refreshToken);
    }

    private String indexKey(long userId) {
        return "auth:refresh-index:" + userId;
    }

    private Set<String> indexMembers(long userId) {
        Set<String> members = redisTemplate.opsForSet().members(indexKey(userId));
        return members == null ? Set.of() : members;
    }

    @BeforeEach
    void clearUserKeys() {
        token = randomToken();
        List<String> keys = new ArrayList<>();
        for (String pattern : List.of("auth:refresh:*", "auth:refresh-index:*",
                "auth:refresh-revoked:*", "auth:refresh-consumed:*", "auth:refresh-barrier:*")) {
            redisTemplate.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(pattern).count(1000).build()).forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Nested
    @DisplayName("저장")
    class Save {

        @Test
        @DisplayName("토큰 원문이 아니라 SHA-256 해시를 키로 쓴다")
        void keysByHashNotRawToken() {
            store.save(token, USER_ID, TTL);

            assertThat(redisTemplate.hasKey(tokenKey(token))).isTrue();
            assertThat(redisTemplate.hasKey("auth:refresh:" + token)).isFalse();
        }

        @Test
        @DisplayName("사용자별 세션 인덱스에 해시를 넣고 TTL을 맞춘다")
        void indexesSessionForUser() {
            store.save(token, USER_ID, TTL);

            assertThat(indexMembers(USER_ID)).containsExactly(Hashes.sha256Hex(token));
            assertThat(redisTemplate.getExpire(indexKey(USER_ID), TimeUnit.SECONDS))
                    .isGreaterThan(TTL.toSeconds() - 60);
        }
    }

    @Nested
    @DisplayName("소비")
    class Consume {

        @Test
        @DisplayName("한 번에 읽고 지운 뒤 인덱스에서도 뺀다")
        void readsAndDeletesAndDeindexes() {
            store.save(token, USER_ID, TTL);

            assertThat(store.consume(token)).contains(USER_ID);
            assertThat(redisTemplate.hasKey(tokenKey(token))).isFalse();
            assertThat(indexMembers(USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("없는 토큰이면 빈 값이고 회전 흔적도 남기지 않는다")
        void leavesNoTraceOnMiss() {
            assertThat(store.consume(token)).isEmpty();
            assertThat(store.wasRecentlyConsumed(token)).isFalse();
        }

        @Test
        @DisplayName("방금 회전했다는 흔적을 남긴다")
        void marksRecentlyConsumed() {
            store.save(token, USER_ID, TTL);
            store.consume(token);

            assertThat(store.wasRecentlyConsumed(token)).isTrue();
        }
    }

    @Nested
    @DisplayName("의도된 폐기")
    class Revoke {

        @Test
        @DisplayName("토큰을 지우고 폐기 흔적을 남긴다")
        void deletesAndMarks() {
            store.save(token, USER_ID, TTL);
            store.revoke(token);

            assertThat(redisTemplate.hasKey(tokenKey(token))).isFalse();
            assertThat(indexMembers(USER_ID)).isEmpty();
            assertThat(store.wasRevoked(token)).isTrue();
        }

        @Test
        @DisplayName("이미 사라진 토큰이어도 흔적은 남긴다")
        void marksEvenWhenAlreadyGone() {
            store.revoke(token);

            assertThat(store.wasRevoked(token)).isTrue();
        }

        @Test
        @DisplayName("흔적이 없으면 wasRevoked가 거짓이다")
        void reportsNotRevoked() {
            assertThat(store.wasRevoked(token)).isFalse();
        }
    }

    @Nested
    @DisplayName("전체 폐기")
    class RevokeAll {

        @Test
        @DisplayName("인덱스에 있는 모든 세션을 지우고 개수를 돌려준다")
        void deletesEverySession() {
            List<String> tokens = List.of(randomToken(), randomToken(), randomToken());
            tokens.forEach(t -> store.save(t, USER_ID, TTL));

            assertThat(store.revokeAll(USER_ID)).isEqualTo(3);
            tokens.forEach(t -> assertThat(redisTemplate.hasKey(tokenKey(t))).isFalse());
            assertThat(redisTemplate.hasKey(indexKey(USER_ID))).isFalse();
        }

        @Test
        @DisplayName("폐기한 세션마다 흔적을 남겨 연쇄 전체 폐기를 막는다")
        void marksEveryRevokedSession() {
            String other = randomToken();
            store.save(token, USER_ID, TTL);
            store.save(other, USER_ID, TTL);

            store.revokeAll(USER_ID);

            assertThat(store.wasRevoked(token)).isTrue();
            assertThat(store.wasRevoked(other)).isTrue();
        }

        @Test
        @DisplayName("만료돼 이미 사라진 세션은 폐기 개수에 넣지 않는다")
        void countsOnlyKeysActuallyDeleted() {
            store.save(token, USER_ID, TTL);
            redisTemplate.delete(tokenKey(token));

            assertThat(store.revokeAll(USER_ID)).isZero();
        }

        @Test
        @DisplayName("세션이 없어도 예외 없이 0을 돌려준다")
        void toleratesEmptyIndex() {
            assertThat(store.revokeAll(USER_ID)).isZero();
        }

        @Test
        @DisplayName("다른 사용자의 세션은 건드리지 않는다")
        void leavesOtherUsersAlone() {
            String mine = randomToken();
            String theirs = randomToken();
            store.save(mine, USER_ID, TTL);
            store.save(theirs, 99L, TTL);

            store.revokeAll(USER_ID);

            assertThat(redisTemplate.hasKey(tokenKey(theirs))).isTrue();
        }
    }

    @Nested
    @DisplayName("동시성 — mock으로는 표현할 수 없던 것들")
    class Concurrency {

        private static final int THREADS = 16;

        private <T> List<T> runTogether(Callable<T> task) throws Exception {
            try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<T>> futures = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return task.call();
                    }));
                }
                start.countDown();

                List<T> results = new ArrayList<>();
                for (Future<T> future : futures) {
                    results.add(future.get(10, TimeUnit.SECONDS));
                }
                return results;
            }
        }

        @RepeatedTest(10)
        @DisplayName("같은 토큰으로 동시에 회전해도 정확히 하나만 이긴다")
        void exactlyOneWinnerOnConcurrentRotation() throws Exception {
            store.save(token, USER_ID, TTL);

            List<Optional<Long>> results = runTogether(() -> store.consume(token));

            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
        }

        @RepeatedTest(10)
        @DisplayName("진 요청은 반드시 회전 흔적을 본다 — 정상 사용자를 탈취로 오판하면 안 된다")
        void losersAlwaysSeeTheConsumedMarker() throws Exception {
            store.save(token, USER_ID, TTL);

            AtomicInteger sawNeither = new AtomicInteger();
            runTogether(() -> {
                boolean won = store.consume(token).isPresent();
                // AuthService가 재사용 판정을 내리기 직전에 보는 상태와 같다.
                if (!won && !store.wasRecentlyConsumed(token) && !store.wasRevoked(token)) {
                    sawNeither.incrementAndGet();
                }
                return won;
            });

            assertThat(sawNeither.get())
                    .withFailMessage("토큰도 흔적도 못 본 요청이 %d건 — 이 요청들은 모든 세션을 폐기시킨다",
                            sawNeither.get())
                    .isZero();
        }

        @RepeatedTest(10)
        @DisplayName("발급과 전체 폐기가 겹쳐도 인덱스 밖에 살아남는 세션이 없다")
        void neverLeavesAnOrphanSession() throws Exception {
            List<String> issued = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                issued.add(randomToken());
            }
            AtomicInteger next = new AtomicInteger();

            runTogether(() -> {
                int slot = next.getAndIncrement();
                if (slot % 4 == 0) {
                    return store.revokeAll(USER_ID);
                }
                store.save(issued.get(slot), USER_ID, TTL);
                return 0;
            });

            Set<String> indexed = indexMembers(USER_ID);
            List<String> orphans = issued.stream()
                    .filter(t -> Boolean.TRUE.equals(redisTemplate.hasKey(tokenKey(t))))
                    .filter(t -> !indexed.contains(Hashes.sha256Hex(t)))
                    .toList();

            assertThat(orphans)
                    .withFailMessage("인덱스에 없는데 살아 있는 토큰 %d건 — 다시는 일괄 폐기되지 않는다",
                            orphans.size())
                    .isEmpty();
        }

        @Test
        @DisplayName("전체 폐기 직후의 회전은 새 토큰을 심지 못한다")
        void rotationLosesToBulkRevoke() {
            store.save(token, USER_ID, TTL);
            store.consume(token);
            store.revokeAll(USER_ID);

            String rotated = randomToken();

            assertThat(store.saveRotated(rotated, USER_ID, TTL)).isFalse();
            assertThat(redisTemplate.hasKey(tokenKey(rotated))).isFalse();
        }

        @Test
        @DisplayName("전체 폐기 뒤의 새 로그인은 막지 않는다")
        void freshLoginIsNotBlockedByBarrier() {
            store.revokeAll(USER_ID);

            String fresh = randomToken();
            store.save(fresh, USER_ID, TTL);

            assertThat(redisTemplate.hasKey(tokenKey(fresh))).isTrue();
        }
    }
}

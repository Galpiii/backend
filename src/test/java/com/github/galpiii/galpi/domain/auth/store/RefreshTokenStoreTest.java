package com.github.galpiii.galpi.domain.auth.store;

import com.github.galpiii.galpi.global.util.Hashes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RefreshTokenStore — 세션 인덱스와 재사용 흔적")
class RefreshTokenStoreTest {

    private static final long USER_ID = 7L;
    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.refresh.payload";
    private static final Duration TTL = Duration.ofDays(14);

    private static final String TOKEN_KEY = "auth:refresh:" + Hashes.sha256Hex(TOKEN);
    private static final String INDEX_KEY = "auth:refresh-index:" + USER_ID;
    private static final String REVOKED_KEY = "auth:refresh-revoked:" + Hashes.sha256Hex(TOKEN);
    private static final String CONSUMED_KEY = "auth:refresh-consumed:" + Hashes.sha256Hex(TOKEN);

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SetOperations<String, String> setOperations;

    private RefreshTokenStore store;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        store = new RefreshTokenStore(redisTemplate);
    }

    @Nested
    @DisplayName("저장")
    class Save {

        @Test
        @DisplayName("토큰 원문이 아니라 SHA-256 해시를 키로 쓴다")
        void keysByHashNotRawToken() {
            store.save(TOKEN, USER_ID, TTL);

            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(valueOperations).set(key.capture(), eq(String.valueOf(USER_ID)), eq(TTL));
            assertThat(key.getValue())
                    .isEqualTo(TOKEN_KEY)
                    .doesNotContain(TOKEN);
        }

        @Test
        @DisplayName("사용자별 세션 인덱스에 해시를 넣고 TTL을 맞춘다")
        void indexesSessionForUser() {
            store.save(TOKEN, USER_ID, TTL);

            verify(setOperations).add(INDEX_KEY, Hashes.sha256Hex(TOKEN));
            verify(redisTemplate).expire(INDEX_KEY, TTL);
        }
    }

    @Nested
    @DisplayName("소비")
    class Consume {

        @Test
        @DisplayName("한 번에 읽고 지운 뒤 인덱스에서도 뺀다")
        void readsAndDeletesAtomically() {
            given(valueOperations.getAndDelete(TOKEN_KEY)).willReturn(String.valueOf(USER_ID));

            assertThat(store.consume(TOKEN)).contains(USER_ID);

            verify(valueOperations).getAndDelete(TOKEN_KEY);
            verify(setOperations).remove(INDEX_KEY, Hashes.sha256Hex(TOKEN));
        }

        @Test
        @DisplayName("없는 토큰이면 인덱스를 건드리지 않는다")
        void leavesIndexAloneOnMiss() {
            given(valueOperations.getAndDelete(TOKEN_KEY)).willReturn(null);

            assertThat(store.consume(TOKEN)).isEmpty();

            verify(setOperations, never()).remove(any(), any(Object[].class));
        }

        @Test
        @DisplayName("방금 회전했다는 흔적을 남긴다 — 동시 갱신을 탈취로 오판하지 않으려면 필요하다")
        void marksRecentlyConsumed() {
            given(valueOperations.getAndDelete(TOKEN_KEY)).willReturn(String.valueOf(USER_ID));

            store.consume(TOKEN);

            verify(valueOperations).set(eq(CONSUMED_KEY), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("없는 토큰이면 회전 흔적도 남기지 않는다")
        void leavesNoMarkerOnMiss() {
            given(valueOperations.getAndDelete(TOKEN_KEY)).willReturn(null);

            store.consume(TOKEN);

            verify(valueOperations, never()).set(eq(CONSUMED_KEY), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("회전 흔적이 살아 있는 동안만 동시 갱신으로 인정한다")
        void reportsRecentlyConsumedWhileMarkerLives() {
            given(redisTemplate.hasKey(CONSUMED_KEY)).willReturn(true);
            assertThat(store.wasRecentlyConsumed(TOKEN)).isTrue();

            given(redisTemplate.hasKey(CONSUMED_KEY)).willReturn(false);
            assertThat(store.wasRecentlyConsumed(TOKEN)).isFalse();
        }
    }

    @Nested
    @DisplayName("의도된 폐기")
    class Revoke {

        @Test
        @DisplayName("토큰을 지우고 폐기 흔적을 남긴다")
        void deletesAndMarks() {
            given(valueOperations.getAndDelete(TOKEN_KEY)).willReturn(String.valueOf(USER_ID));

            store.revoke(TOKEN);

            verify(valueOperations).getAndDelete(TOKEN_KEY);
            verify(setOperations).remove(INDEX_KEY, Hashes.sha256Hex(TOKEN));
            verify(valueOperations).set(eq(REVOKED_KEY), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("이미 사라진 토큰이어도 흔적은 남긴다")
        void marksEvenWhenAlreadyGone() {
            given(valueOperations.getAndDelete(TOKEN_KEY)).willReturn(null);

            store.revoke(TOKEN);

            verify(valueOperations).set(eq(REVOKED_KEY), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("폐기 흔적이 있으면 wasRevoked가 참이다")
        void reportsRevoked() {
            given(redisTemplate.hasKey(REVOKED_KEY)).willReturn(true);

            assertThat(store.wasRevoked(TOKEN)).isTrue();
        }

        @Test
        @DisplayName("흔적이 없으면 wasRevoked가 거짓이다")
        void reportsNotRevoked() {
            given(redisTemplate.hasKey(REVOKED_KEY)).willReturn(null);

            assertThat(store.wasRevoked(TOKEN)).isFalse();
        }
    }

    @Nested
    @DisplayName("전체 폐기")
    class RevokeAll {

        @Test
        @DisplayName("인덱스에 있는 모든 세션을 지우고 개수를 돌려준다")
        void deletesEverySession() {
            given(setOperations.members(INDEX_KEY)).willReturn(Set.of("hash-a", "hash-b"));

            assertThat(store.revokeAll(USER_ID)).isEqualTo(2);

            verify(redisTemplate).delete(Set.of("auth:refresh:hash-a", "auth:refresh:hash-b"));
            verify(redisTemplate).delete(INDEX_KEY);
        }

        @Test
        @DisplayName("폐기한 세션마다 흔적을 남겨 연쇄 전체 폐기를 막는다")
        void marksEveryRevokedSession() {
            given(setOperations.members(INDEX_KEY)).willReturn(Set.of("hash-a", "hash-b"));

            store.revokeAll(USER_ID);

            verify(valueOperations).set(eq("auth:refresh-revoked:hash-a"), eq("1"), any(Duration.class));
            verify(valueOperations).set(eq("auth:refresh-revoked:hash-b"), eq("1"), any(Duration.class));
        }

        @Test
        @DisplayName("세션이 없으면 0을 돌려주고 지울 키를 만들지 않는다")
        void toleratesEmptyIndex() {
            given(setOperations.members(INDEX_KEY)).willReturn(Set.of());

            assertThat(store.revokeAll(USER_ID)).isZero();

            verify(redisTemplate).delete(INDEX_KEY);
            verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("인덱스 자체가 없어도 예외 없이 끝낸다")
        void toleratesMissingIndex() {
            given(setOperations.members(INDEX_KEY)).willReturn(null);

            assertThat(store.revokeAll(USER_ID)).isZero();
        }
    }
}

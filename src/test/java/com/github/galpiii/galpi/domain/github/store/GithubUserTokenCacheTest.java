package com.github.galpiii.galpi.domain.github.store;

import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenEncryptionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GithubUserTokenCache — 암호문만 캐싱")
class GithubUserTokenCacheTest {

    private static final long USER_ID = 7L;
    private static final String KEY = "github:user-token:" + USER_ID;
    private static final String TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz012345";
    private static final Duration TTL = Duration.ofHours(8);

    private static final String KEY_V1 = randomKey();
    private static final String KEY_V2 = randomKey();

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private GithubUserTokenCache cache;

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static TokenCipher cipher(int currentVersion, Map<Integer, String> keys) {
        return new TokenCipher(new TokenEncryptionProperties(currentVersion, keys));
    }

    private GithubUserTokenCache cacheWith(TokenCipher tokenCipher) {
        return new GithubUserTokenCache(redisTemplate, tokenCipher);
    }

    private String capturedValue() {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(any(), value.capture(), any(Duration.class));
        return value.getValue();
    }

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        cache = cacheWith(cipher(1, Map.of(1, KEY_V1)));
    }

    @Test
    @DisplayName("평문 토큰을 Redis에 남기지 않는다")
    void neverStoresPlaintext() {
        cache.put(USER_ID, TOKEN, TTL);

        assertThat(capturedValue())
                .doesNotContain(TOKEN)
                .doesNotContain("ghu_");
    }

    @Test
    @DisplayName("암호문 앞에 키 버전을 붙여 저장한다")
    void prefixesKeyVersion() {
        cache.put(USER_ID, TOKEN, TTL);

        assertThat(capturedValue()).startsWith("1:");
    }

    @Test
    @DisplayName("저장한 토큰을 그대로 되돌려준다")
    void roundTrips() {
        cache.put(USER_ID, TOKEN, TTL);
        String stored = capturedValue();
        given(valueOperations.get(KEY)).willReturn(stored);

        assertThat(cache.find(USER_ID)).contains(TOKEN);
    }

    @Test
    @DisplayName("키를 로테이션해도 이전 버전으로 캐시된 항목을 읽는다")
    void decryptsPreviousKeyVersion() {
        cacheWith(cipher(1, Map.of(1, KEY_V1))).put(USER_ID, TOKEN, TTL);
        String storedWithV1 = capturedValue();

        GithubUserTokenCache rotated = cacheWith(cipher(2, Map.of(1, KEY_V1, 2, KEY_V2)));
        given(valueOperations.get(KEY)).willReturn(storedWithV1);

        assertThat(rotated.find(USER_ID)).contains(TOKEN);
    }

    @Test
    @DisplayName("TTL이 없거나 이미 지났으면 저장하지 않는다")
    void skipsWhenTtlNotPositive() {
        cache.put(USER_ID, TOKEN, null);
        cache.put(USER_ID, TOKEN, Duration.ZERO);
        cache.put(USER_ID, TOKEN, Duration.ofSeconds(-1));

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("캐시가 비면 빈 값을 준다")
    void returnsEmptyOnMiss() {
        given(valueOperations.get(KEY)).willReturn(null);

        assertThat(cache.find(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("복호화할 수 없는 항목은 버리고 캐시 미스로 처리한다")
    void discardsUndecryptableEntry() {
        // 키를 잃어버렸거나 값이 손상된 경우. 여기서 예외가 나가면 /auth/me 전체가 죽는다.
        given(valueOperations.get(KEY)).willReturn("1:not-a-valid-ciphertext");

        assertThat(cache.find(USER_ID)).isEmpty();
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("버전 접두사가 없는 옛 형식 항목도 버린다")
    void discardsLegacyPlaintextEntry() {
        // 이번 변경 전에 평문으로 캐시된 항목이 남아 있을 수 있다.
        given(valueOperations.get(KEY)).willReturn(TOKEN);

        assertThat(cache.find(USER_ID)).isEmpty();
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("모르는 키 버전이면 버린다")
    void discardsUnknownKeyVersion() {
        cacheWith(cipher(2, Map.of(1, KEY_V1, 2, KEY_V2))).put(USER_ID, TOKEN, TTL);
        String storedWithV2 = capturedValue();
        given(valueOperations.get(KEY)).willReturn(storedWithV2);

        // v2 키를 모르는 인스턴스가 읽으면 조용히 미스가 돼야 한다.
        assertThat(cacheWith(cipher(1, Map.of(1, KEY_V1))).find(USER_ID)).isEmpty();
    }
}

package com.github.galpiii.galpi.domain.auth.store;

import com.github.galpiii.galpi.global.util.Hashes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoginCodeStore — 일회용 로그인 코드")
class LoginCodeStoreTest {

    private static final long USER_ID = 7L;
    private static final Duration TTL = Duration.ofSeconds(60);

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private LoginCodeStore store;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        store = new LoginCodeStore(redisTemplate);
    }

    private String issueAndCaptureKey() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(key.capture(), eq(String.valueOf(USER_ID)), eq(TTL));
        return key.getValue();
    }

    @Test
    @DisplayName("코드 원문이 아니라 SHA-256 해시를 키로 쓴다")
    void keysByHashNotRawCode() {
        String code = store.issue(USER_ID, TTL);

        assertThat(issueAndCaptureKey())
                .isEqualTo("auth:login-code:" + Hashes.sha256Hex(code))
                .doesNotContain(code);
    }

    @Test
    @DisplayName("발급할 때마다 다른 코드를 준다")
    void issuesUniqueCodes() {
        long distinct = Stream.generate(() -> store.issue(USER_ID, TTL))
                .limit(50)
                .distinct()
                .count();

        assertThat(distinct).isEqualTo(50);
    }

    @Test
    @DisplayName("추측하기 어려운 길이를 가진다")
    void issuesLongEnoughCode() {
        // 32바이트를 URL-safe Base64로 인코딩하면 43자다.
        assertThat(store.issue(USER_ID, TTL)).hasSize(43);
    }

    @Test
    @DisplayName("읽기와 삭제를 한 번에 해서 두 번 쓰이지 않게 한다")
    void consumesAtomically() {
        String key = "auth:login-code:" + Hashes.sha256Hex("code");
        given(valueOperations.getAndDelete(key)).willReturn(String.valueOf(USER_ID));

        assertThat(store.consume("code")).contains(USER_ID);

        // get 후 delete로 나누면 그 사이에 같은 코드가 두 번 교환될 수 있다.
        verify(valueOperations).getAndDelete(key);
    }

    @Test
    @DisplayName("없는 코드면 비어 있다")
    void returnsEmptyForUnknownCode() {
        given(valueOperations.getAndDelete("auth:login-code:" + Hashes.sha256Hex("nope")))
                .willReturn(null);

        assertThat(store.consume("nope")).isEmpty();
    }
}

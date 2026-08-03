package com.github.galpiii.galpi.domain.github.store;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuthCodeGuard — 인증 코드 1회 사용 보장")
class OAuthCodeGuardTest {

    private static final String CODE = "gh-oauth-code";
    private static final String KEY = "oauth:code:" + Hashes.sha256Hex(CODE);

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private OAuthCodeGuard guard;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        guard = new OAuthCodeGuard(redisTemplate);
    }

    @Test
    @DisplayName("코드 원문이 아니라 SHA-256 해시를 키로 쓴다")
    void keysByHashNotRawCode() {
        given(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).willReturn(true);

        guard.markUsed(CODE);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(key.capture(), eq("1"), any(Duration.class));
        assertThat(key.getValue()).isEqualTo(KEY).doesNotContain(CODE);
    }

    @Test
    @DisplayName("SETNX로 원자적으로 선점해 동시 콜백에서 한쪽만 통과시킨다")
    void claimsAtomically() {
        given(valueOperations.setIfAbsent(eq(KEY), eq("1"), any(Duration.class))).willReturn(true);

        assertThat(guard.markUsed(CODE)).isTrue();

        // exists 후 set으로 나누면 동시에 도착한 두 콜백이 모두 통과한다.
        verify(valueOperations).setIfAbsent(eq(KEY), eq("1"), any(Duration.class));
    }

    @Test
    @DisplayName("이미 선점된 코드는 거절한다")
    void rejectsAlreadyUsedCode() {
        given(valueOperations.setIfAbsent(eq(KEY), eq("1"), any(Duration.class))).willReturn(false);

        assertThat(guard.markUsed(CODE)).isFalse();
    }

    @Test
    @DisplayName("Redis가 null을 주면 안전한 쪽(거절)으로 판단한다")
    void failsClosedOnNullReply() {
        given(valueOperations.setIfAbsent(eq(KEY), eq("1"), any(Duration.class))).willReturn(null);

        assertThat(guard.markUsed(CODE)).isFalse();
    }
}

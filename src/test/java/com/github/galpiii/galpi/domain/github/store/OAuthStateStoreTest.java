package com.github.galpiii.galpi.domain.github.store;

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

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OAuthStateStore — CSRF state")
class OAuthStateStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private OAuthStateStore store;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        store = new OAuthStateStore(redisTemplate);
    }

    @Test
    @DisplayName("복귀 경로를 값으로 담고 5분 TTL을 준다")
    void storesReturnPathWithTtl() {
        String state = store.issue("/projects/3");

        verify(valueOperations).set("oauth:state:" + state, "/projects/3", Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("복귀 경로가 없으면 빈 문자열로 저장한다")
    void storesEmptyStringWhenNoReturnPath() {
        String state = store.issue(null);

        verify(valueOperations).set("oauth:state:" + state, "", Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("발급할 때마다 예측 불가능한 값을 준다")
    void issuesUniqueStates() {
        long distinct = Stream.generate(() -> store.issue(""))
                .limit(50)
                .distinct()
                .count();

        assertThat(distinct).isEqualTo(50);
    }

    @Test
    @DisplayName("읽기와 삭제를 한 번에 해서 state가 재사용되지 않게 한다")
    void consumesAtomically() {
        given(valueOperations.getAndDelete("oauth:state:abc")).willReturn("/projects/3");

        assertThat(store.consume("abc")).contains("/projects/3");

        verify(valueOperations).getAndDelete("oauth:state:abc");
    }

    @Test
    @DisplayName("복귀 경로가 빈 state도 유효한 state로 취급한다")
    void treatsEmptyValueAsValidState() {
        given(valueOperations.getAndDelete("oauth:state:abc")).willReturn("");

        assertThat(store.consume("abc")).contains("");
    }

    @Test
    @DisplayName("state가 없거나 비면 Redis를 조회하지 않고 거절한다")
    void rejectsBlankStateWithoutLookup() {
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume("  ")).isEmpty();

        verify(valueOperations, never()).getAndDelete(anyString());
    }

    @Test
    @DisplayName("만료·재사용된 state는 비어 있다")
    void returnsEmptyForUnknownState() {
        given(valueOperations.getAndDelete(any())).willReturn(null);

        assertThat(store.consume("expired")).isEmpty();
    }

    @Test
    @DisplayName("state 키와 install-intent 키는 네임스페이스가 겹치지 않는다")
    void separatesInstallIntentNamespace() {
        store.rememberInstallIntent(7L, "/projects", Duration.ofMinutes(10));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(key.capture(), any(), any(Duration.class));
        assertThat(key.getValue()).isEqualTo("github:install-intent:7").doesNotStartWith("oauth:state:");
    }

    @Test
    @DisplayName("install-intent도 한 번 읽으면 사라진다")
    void consumesInstallIntentOnce() {
        given(valueOperations.getAndDelete("github:install-intent:7")).willReturn("/projects");

        assertThat(store.consumeInstallIntent(7L)).contains("/projects");

        verify(valueOperations).getAndDelete("github:install-intent:7");
    }
}

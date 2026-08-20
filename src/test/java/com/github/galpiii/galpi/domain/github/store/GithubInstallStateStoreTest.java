package com.github.galpiii.galpi.domain.github.store;

import com.github.galpiii.galpi.domain.github.store.GithubInstallStateStore.InstallIntent;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GithubInstallStateStore — 설치 시작 기록")
class GithubInstallStateStoreTest {

    private static final long USER_ID = 7L;

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private GithubInstallStateStore store;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        store = new GithubInstallStateStore(redisTemplate, objectMapper);
    }

    private String captureStatePayload(String state) {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("github:install-state:" + state), payload.capture(), any(Duration.class));
        return payload.getValue();
    }

    @Test
    @DisplayName("state 키 하나에만 15분짜리로 적는다")
    void writesStateKeyOnly() {
        String state = store.issue(USER_ID, "/projects/3/repositories", List.of());

        verify(valueOperations).set(eq("github:install-state:" + state), any(), eq(Duration.ofMinutes(15)));
        verify(valueOperations, times(1)).set(any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("기록에 사용자와 복귀 경로가 함께 들어간다")
    void payloadCarriesUserAndReturnTo() {
        String state = store.issue(USER_ID, "/projects/3/repositories", List.of());

        InstallIntent intent = objectMapper.readValue(captureStatePayload(state), InstallIntent.class);
        assertThat(intent.userId()).isEqualTo(USER_ID);
        assertThat(intent.returnTo()).isEqualTo("/projects/3/repositories");
        assertThat(intent.state()).isEqualTo(state);
    }

    @Test
    @DisplayName("발급할 때마다 예측 불가능한 state를 준다")
    void issuesUnpredictableState() {
        assertThat(Stream.generate(() -> store.issue(USER_ID, "/x", List.of())).limit(50).distinct().count())
                .isEqualTo(50);
    }

    @Test
    @DisplayName("state로 한 번 읽으면 사라진다")
    void consumesStateOnce() {
        String state = store.issue(USER_ID, "/projects", List.of());
        String payload = captureStatePayload(state);

        given(valueOperations.getAndDelete("github:install-state:" + state)).willReturn(payload);

        assertThat(store.consumeState(state)).map(InstallIntent::userId).contains(USER_ID);

        verify(valueOperations).getAndDelete("github:install-state:" + state);
    }

    @Test
    @DisplayName("복귀 경로도 기록에서 함께 돌아온다")
    void consumeCarriesReturnTo() {
        String state = store.issue(USER_ID, "/projects", List.of());
        String payload = captureStatePayload(state);

        given(valueOperations.getAndDelete("github:install-state:" + state)).willReturn(payload);

        assertThat(store.consumeState(state)).map(InstallIntent::returnTo).contains("/projects");
    }

    @Test
    @DisplayName("만료·재사용된 기록은 비어 있다")
    void returnsEmptyForMissingRecord() {
        given(valueOperations.getAndDelete(any())).willReturn(null);

        assertThat(store.consumeState("expired")).isEmpty();
    }

    @Test
    @DisplayName("state가 비어 있으면 Redis를 조회하지도 않는다")
    void skipsLookupForBlankState() {
        assertThat(store.consumeState(null)).isEmpty();
        assertThat(store.consumeState("  ")).isEmpty();
    }

    @Test
    @DisplayName("읽을 수 없는 값이 들어 있으면 기록이 없는 것으로 본다")
    void treatsCorruptPayloadAsAbsent() {
        given(valueOperations.getAndDelete("github:install-state:s")).willReturn("not-json");

        Optional<InstallIntent> intent = store.consumeState("s");

        assertThat(intent).isEmpty();
    }
}

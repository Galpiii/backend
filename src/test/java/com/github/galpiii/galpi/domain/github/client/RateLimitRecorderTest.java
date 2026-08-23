package com.github.galpiii.galpi.domain.github.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RateLimitRecorder — 자격증명별 격리")
class RateLimitRecorderTest {

    @Test
    @DisplayName("서로 다른 토큰의 같은 resource 응답이 서로를 덮어쓰지 않는다")
    void isolatesSnapshotsByCredential() {
        RateLimitRecorder recorder = new RateLimitRecorder();
        RateLimitSnapshot first = new RateLimitSnapshot(
                5_000, 90, 4_910, Instant.parse("2026-08-19T00:10:00Z"), "core");
        RateLimitSnapshot second = new RateLimitSnapshot(
                5_000, 4_000, 1_000, Instant.parse("2026-08-19T00:20:00Z"), "core");

        recorder.record("Bearer token-a", first);
        recorder.record("Bearer token-b", second);

        assertThat(recorder.latest("token-a", "core")).isEqualTo(first);
        assertThat(recorder.latest("token-b", "core")).isEqualTo(second);
    }
}

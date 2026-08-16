package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.config.GithubOperationProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("사용자별 GitHub 작업 동시 실행 제한")
class GithubUserOperationLimiterTest {

    private final GithubUserOperationLimiter limiter = new GithubUserOperationLimiter(
            new GithubOperationProperties(50, Duration.ofSeconds(30), 1));

    @Test
    @DisplayName("같은 사용자의 두 번째 작업은 즉시 429로 거부한다")
    void rejectsConcurrentOperationForSameUser() {
        String result = limiter.execute(7L, () -> {
            assertThatThrownBy(() -> limiter.execute(7L, () -> "second"))
                    .isInstanceOf(GithubApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.GITHUB_OPERATION_IN_PROGRESS);
            return "first";
        });

        assertThat(result).isEqualTo("first");
        assertThat(limiter.execute(7L, () -> "after")).isEqualTo("after");
    }

    @Test
    @DisplayName("서로 다른 사용자의 작업은 독립적으로 실행한다")
    void allowsDifferentUsers() {
        assertThat(limiter.execute(7L, () -> limiter.execute(8L, () -> "ok")))
                .isEqualTo("ok");
    }
}

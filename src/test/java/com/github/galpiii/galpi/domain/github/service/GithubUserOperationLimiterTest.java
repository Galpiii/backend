package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.config.GithubOperationProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("사용자별 GitHub 작업 동시 실행 제한")
class GithubUserOperationLimiterTest {

    @Test
    @DisplayName("같은 사용자의 짧은 요청 중첩은 permit을 기다렸다 이어서 실행한다")
    void waitsForOverlappingOperation() throws Exception {
        GithubUserOperationLimiter limiter = limiter(Duration.ofSeconds(2));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> limiter.execute(7L, () -> {
                firstStarted.countDown();
                await(releaseFirst);
                return "first";
            }));
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<String> second = executor.submit(() -> limiter.execute(7L, () -> "second"));
            assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo("second");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("대기 상한까지 permit이 나지 않으면 429로 거부한다")
    void rejectsAfterAcquireTimeout() {
        GithubUserOperationLimiter limiter = limiter(Duration.ZERO);

        assertThatThrownBy(() -> limiter.execute(7L, () ->
                limiter.execute(7L, () -> "second")))
                .isInstanceOf(GithubApiException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.GITHUB_OPERATION_IN_PROGRESS);
    }

    @Test
    @DisplayName("서로 다른 사용자의 작업은 독립적으로 실행한다")
    void allowsDifferentUsers() {
        GithubUserOperationLimiter limiter = limiter(Duration.ZERO);
        assertThat(limiter.execute(7L, () -> limiter.execute(8L, () -> "ok")))
                .isEqualTo("ok");
    }

    private static GithubUserOperationLimiter limiter(Duration acquireTimeout) {
        return new GithubUserOperationLimiter(new GithubOperationProperties(
                50, Duration.ofSeconds(30), 1, acquireTimeout));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

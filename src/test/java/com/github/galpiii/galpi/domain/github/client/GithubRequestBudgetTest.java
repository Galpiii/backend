package com.github.galpiii.galpi.domain.github.client;

import com.github.galpiii.galpi.domain.github.config.GithubOperationProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GitHub 사용자 작업 budget")
class GithubRequestBudgetTest {

    @Test
    @DisplayName("설정한 HTTP 요청 수를 넘기면 작업을 중단한다")
    void rejectsCallsBeyondRequestLimit() {
        GithubRequestBudget budget = GithubRequestBudget.from(
                new GithubOperationProperties(2, Duration.ofSeconds(30), 1));

        assertThatCode(budget::consume).doesNotThrowAnyException();
        assertThatCode(budget::consume).doesNotThrowAnyException();
        assertThatThrownBy(budget::consume)
                .isInstanceOf(GithubApiException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.GITHUB_OPERATION_BUDGET_EXCEEDED);
    }

    @Test
    @DisplayName("deadline이 지나면 남은 요청 수와 무관하게 중단한다")
    void rejectsCallsAfterDeadline() {
        GithubRequestBudget budget = GithubRequestBudget.from(
                new GithubOperationProperties(10, Duration.ofNanos(1), 1));

        assertThatThrownBy(budget::consume)
                .isInstanceOf(GithubApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GITHUB_OPERATION_TIMEOUT);
    }
}

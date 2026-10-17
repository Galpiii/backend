package com.github.galpiii.galpi.ai.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * OpenAI 호출 설정. 클라이언트·재시도는 공통이고, 모델·출력 상한·예산은 용도별로 둔다.
 *
 * @param timeout       클라이언트 호출 하나의 타임아웃
 * @param maxAttempts   기능명세서 추출의 재시도 상한
 * @param retryBackoff  재시도 사이 첫 대기 시간
 * @param extraction    기능명세서 추출
 * @param summary       PR 요약
 * @param matching      기능대조
 */
@Validated
@ConfigurationProperties(prefix = "galpi.openai")
public record OpenAiProperties(
        @NotBlank String apiKey,
        Duration timeout,
        @Min(1) @Max(10) int maxAttempts,
        Duration retryBackoff,
        @NotNull @Valid ModelSettings extraction,
        @NotNull @Valid ModelSettings summary,
        @NotNull @Valid ModelSettings matching
) {
    /**
     * @param budget 호출 한 건이 재시도까지 포함해 쓸 수 있는 시간
     */
    public record ModelSettings(
            @NotBlank String model,
            @Min(1) long maxOutputTokens,
            @NotNull Duration budget) {
        @AssertTrue
        public boolean isBudgetPositive() {
            return budget != null && !budget.isZero() && !budget.isNegative();
        }
    }
}

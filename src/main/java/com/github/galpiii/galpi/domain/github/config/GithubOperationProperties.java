package com.github.galpiii.galpi.domain.github.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 한 번의 사용자 작업이 GitHub API와 서버 자원을 점유할 수 있는 상한. */
@Validated
@ConfigurationProperties(prefix = "galpi.github.operation")
public record GithubOperationProperties(
        @DefaultValue("50") @Min(1) int maxRequests,
        @DefaultValue("30s") @NotNull Duration timeout,
        @DefaultValue("1") @Min(1) int maxConcurrentPerUser
) {

    public GithubOperationProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("galpi.github.operation.timeout은 양수여야 합니다.");
        }
    }
}

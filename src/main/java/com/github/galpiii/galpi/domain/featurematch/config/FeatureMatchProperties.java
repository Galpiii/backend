package com.github.galpiii.galpi.domain.featurematch.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("galpi.feature-match")
public record FeatureMatchProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("10m") Duration lease,
        @DefaultValue("5s") Duration retryBackoff,
        @DefaultValue("3") @Min(1) @Max(10) int maxAttempts,
        @DefaultValue("4") @Min(1) @Max(16) int maxConcurrency,
        @DefaultValue("120000") @Min(1000) int maxInputChars) {
    @AssertTrue(message = "워커 시간 설정은 양수여야 합니다.")
    public boolean isDurationsValid() {
        return positive(pollInterval) && positive(lease) && positive(retryBackoff);
    }

    private static boolean positive(Duration value) {
        return value != null && value.toMillis() >= 100;
    }
}

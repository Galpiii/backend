package com.github.galpiii.galpi.domain.featurespec.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "galpi.feature-extraction")
public record FeatureExtractionProperties(
        @Min(1) int corePoolSize,
        @Min(1) int maxPoolSize,
        @Min(0) int queueCapacity,
        Duration awaitTermination
) {
}

package com.github.galpiii.galpi.domain.featurespec.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "galpi.feature-extraction")
public record FeatureExtractionProperties(
        @DefaultValue("3") @Min(1) int corePoolSize,
        @DefaultValue("3") @Min(1) int maxPoolSize,
        @DefaultValue("20") @Min(0) int queueCapacity,
        @DefaultValue("60s") Duration awaitTermination
) {
}

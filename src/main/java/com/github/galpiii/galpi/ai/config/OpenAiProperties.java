package com.github.galpiii.galpi.ai.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "galpi.openai")
public record OpenAiProperties(
        @NotBlank String apiKey,
        @DefaultValue("gpt-5") String model,
        @DefaultValue("128000") @Min(1) long maxOutputTokens,
        @DefaultValue("10m") Duration timeout,
        @DefaultValue("3") @Min(1) int maxAttempts,
        @DefaultValue("1s") Duration retryBackoff,
        @DefaultValue("15m") Duration analysisBudget
) {
}

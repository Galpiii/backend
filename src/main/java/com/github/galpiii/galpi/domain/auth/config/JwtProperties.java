package com.github.galpiii.galpi.domain.auth.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "galpi.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @DefaultValue("galpi") String issuer,
        @DefaultValue("30m") Duration accessTokenTtl,
        @DefaultValue("14d") Duration refreshTokenTtl,
        @DefaultValue("60s") Duration loginCodeTtl,
        @DefaultValue Cookie cookie
) {
    public record Cookie(
            @DefaultValue("galpi_refresh") String name,
            @DefaultValue("/auth") String path,
            @DefaultValue("true") boolean secure,
            @DefaultValue("Lax") String sameSite,
            @DefaultValue("") String domain
    ) {
    }
}

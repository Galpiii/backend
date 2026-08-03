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
        /**
         * 회전과 무관하게 세션 하나가 살 수 있는 최대 기간. 회전할 때마다 refresh TTL이 새로
         * 붙으므로, 이 상한이 없으면 활성 세션은 무한히 연장된다.
         */
        @DefaultValue("90d") Duration sessionAbsoluteTtl,
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

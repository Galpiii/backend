package com.github.galpiii.galpi.domain.github.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "galpi.github")
public record GithubAppProperties(
        @NotBlank String appId,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String privateKey,
        @NotBlank String baseUrl,
        @DefaultValue("2022-11-28") String apiVersion,
        @DefaultValue("https://api.github.com") String apiBaseUrl,
        @DefaultValue("https://github.com") String oauthBaseUrl,
        @DefaultValue("Galpi") String userAgent,
        @DefaultValue List<String> allowedRedirectOrigins,
        @NotBlank String defaultRedirectUri,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("10") int maxPages
) {

    public String normalizedPrivateKey() {
        return privateKey.replace("\\n", "\n").trim();
    }

    public String oauthCallbackUrl() {
        return trimTrailingSlash(baseUrl) + "/auth/github/callback";
    }

    public String authorizeUrl() {
        return trimTrailingSlash(oauthBaseUrl) + "/login/oauth/authorize";
    }

    public String accessTokenUrl() {
        return trimTrailingSlash(oauthBaseUrl) + "/login/oauth/access_token";
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

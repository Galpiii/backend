package com.github.galpiii.galpi.domain.github.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubAccessTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Long expiresInSeconds,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("refresh_token_expires_in") Long refreshTokenExpiresInSeconds,
        @JsonProperty("error") String error,
        @JsonProperty("error_description") String errorDescription
) {

    public boolean isError() {
        return error != null || accessToken == null || accessToken.isBlank();
    }

    public boolean hasExpiry() {
        return expiresInSeconds != null && expiresInSeconds > 0;
    }

    public Duration expiresIn() {
        return hasExpiry() ? Duration.ofSeconds(expiresInSeconds) : null;
    }

    @Override
    public String toString() {
        return "GithubAccessTokenResponse[tokenType=" + tokenType
                + ", expiresInSeconds=" + expiresInSeconds
                + ", hasRefreshToken=" + (refreshToken != null)
                + ", error=" + error + "]";
    }
}

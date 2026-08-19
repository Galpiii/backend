package com.github.galpiii.galpi.domain.github.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * {@code POST /app/installations/{id}/access_tokens} 응답.
 *
 * <p>{@code token}은 최대 1시간짜리 자격증명이다. DB에 저장하지 않고 Redis 캐시에만 둔다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubInstallationAccessTokenResponse(
        @JsonProperty("token") String token,
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("repository_selection") String repositorySelection
) {
}

package com.github.galpiii.galpi.domain.github.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubUserResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("login") String login,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("email") String email,
        @JsonProperty("name") String name
) {
}

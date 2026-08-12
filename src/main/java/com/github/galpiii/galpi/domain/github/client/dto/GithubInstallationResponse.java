package com.github.galpiii.galpi.domain.github.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubInstallationResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("account") Account account,
        @JsonProperty("repository_selection") String repositorySelection
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(
            @JsonProperty("id") Long id,
            @JsonProperty("login") String login,
            @JsonProperty("type") String type,
            @JsonProperty("avatar_url") String avatarUrl
    ) {
    }

    public boolean isOrganization() {
        return account != null && "Organization".equalsIgnoreCase(account.type());
    }

    public String accountLogin() {
        return account == null ? null : account.login();
    }
}

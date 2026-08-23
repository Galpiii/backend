package com.github.galpiii.galpi.domain.github.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubRepositoryResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("name") String name,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("owner") Owner owner,
        @JsonProperty("private") Boolean isPrivate,
        @JsonProperty("default_branch") String defaultBranch,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("permissions") Map<String, Boolean> permissions,
        // 아래 셋은 선택 화면 표시용이다. /user/installations/{id}/repositories 응답에 이미
        // 들어 있어 추가 호출이 필요 없고, 저장하지도 않는다.
        @JsonProperty("description") String description,
        @JsonProperty("language") String language,
        @JsonProperty("pushed_at") OffsetDateTime pushedAt
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(
            @JsonProperty("id") Long id,
            @JsonProperty("login") String login,
            @JsonProperty("type") String type
    ) {
    }

    public String ownerLogin() {
        if (owner != null && owner.login() != null) {
            return owner.login();
        }
        // full_name이 "owner/name"이라 owner가 비어 와도 여기서 복구된다.
        int slash = fullName == null ? -1 : fullName.indexOf('/');
        return slash > 0 ? fullName.substring(0, slash) : null;
    }
}

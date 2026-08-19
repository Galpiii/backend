package com.github.galpiii.galpi.domain.github.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code GET /user/installations}는 배열이 아니라 감싼 객체를 돌려준다. 페이지네이션은 여전히
 * Link 헤더로 하되, 각 페이지에서 목록을 꺼내는 단계가 하나 더 필요하다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubInstallationsPage(
        @JsonProperty("total_count") Integer totalCount,
        @JsonProperty("installations") List<GithubInstallationResponse> installations
) {

    public List<GithubInstallationResponse> items() {
        return installations == null ? List.of() : installations;
    }
}

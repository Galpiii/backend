package com.github.galpiii.galpi.domain.github.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** {@code GET /user/installations/{id}/repositories}의 페이지 형태. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubRepositoriesPage(
        @JsonProperty("total_count") Integer totalCount,
        @JsonProperty("repository_selection") String repositorySelection,
        @JsonProperty("repositories") List<GithubRepositoryResponse> repositories
) {

    public List<GithubRepositoryResponse> items() {
        return repositories == null ? List.of() : repositories;
    }
}

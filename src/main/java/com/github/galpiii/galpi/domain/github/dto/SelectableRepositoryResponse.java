package com.github.galpiii.galpi.domain.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;

import java.util.Map;

/**
 * 선택 화면에 뿌리는 저장소 한 건. 이 목록은 DB에 저장하지 않는다 — 매번 GitHub에서 읽는다.
 */
public record SelectableRepositoryResponse(
        Long githubRepositoryId,
        String owner,
        String name,
        String fullName,
        @JsonProperty("private") boolean isPrivate,
        String defaultBranch,
        String htmlUrl,
        Map<String, Boolean> permissions,
        boolean linked
) {

    public static SelectableRepositoryResponse of(GithubRepositoryResponse repository,
                                                  boolean linked) {
        return new SelectableRepositoryResponse(
                repository.id(),
                repository.ownerLogin(),
                repository.name(),
                repository.fullName(),
                Boolean.TRUE.equals(repository.isPrivate()),
                repository.defaultBranch(),
                repository.htmlUrl(),
                repository.permissions() == null ? Map.of() : repository.permissions(),
                linked);
    }
}

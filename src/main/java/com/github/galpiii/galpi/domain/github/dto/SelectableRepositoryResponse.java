package com.github.galpiii.galpi.domain.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;

import java.time.OffsetDateTime;
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
        // 선택 화면이 저장소마다 설명·언어·최근 업데이트를 보여 준다. 셋 다 GitHub 응답에
        // 이미 들어 있던 값이라 추가 호출은 없다.
        String description,
        String language,
        OffsetDateTime pushedAt,
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
                repository.description(),
                repository.language(),
                repository.pushedAt(),
                repository.permissions() == null ? Map.of() : repository.permissions(),
                linked);
    }
}

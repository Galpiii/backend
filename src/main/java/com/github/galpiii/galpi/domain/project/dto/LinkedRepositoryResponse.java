package com.github.galpiii.galpi.domain.project.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;

import java.time.OffsetDateTime;

/** 프로젝트에 실제로 연결된 저장소. {@code accessStatus}가 경고 배지의 근거가 된다. */
public record LinkedRepositoryResponse(
        Long repositoryId,
        Long githubRepositoryId,
        Long installationId,
        String owner,
        String name,
        String fullName,
        @JsonProperty("private") boolean isPrivate,
        String defaultBranch,
        String htmlUrl,
        String accessStatus,
        OffsetDateTime lastSyncedAt
) {

    public static LinkedRepositoryResponse from(GithubRepository repository) {
        return new LinkedRepositoryResponse(
                repository.getId(),
                repository.getGithubRepositoryId(),
                repository.getInstallationId(),
                repository.getOwner(),
                repository.getName(),
                repository.getFullName(),
                repository.isPrivate(),
                repository.getDefaultBranch(),
                repository.getHtmlUrl(),
                repository.getAccessStatus().name(),
                repository.getLastSyncedAt());
    }
}

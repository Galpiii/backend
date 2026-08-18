package com.github.galpiii.galpi.domain.github.dto;

/**
 * GitHub에서 방금 읽어 온 저장소의 현재 모습.
 *
 * <p>{@code githubRepositoryId}만 불변이고 나머지는 언제든 바뀐다. 저장소를 새로 연결할
 * 때와, 목록을 다시 조회해 기존 연결의 스냅샷을 갱신할 때 같은 값을 쓴다.
 */
public record RepositorySnapshot(
        Long githubRepositoryId,
        Long installationId,
        String owner,
        String name,
        String fullName,
        boolean isPrivate,
        String defaultBranch,
        String htmlUrl
) {
}

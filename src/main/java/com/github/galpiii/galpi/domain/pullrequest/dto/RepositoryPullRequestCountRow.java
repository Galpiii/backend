package com.github.galpiii.galpi.domain.pullrequest.dto;

import java.time.OffsetDateTime;

/**
 * 집계 쿼리가 저장소마다 한 행씩 읽어 오는 값.
 *
 * <p>프로젝트 전체 숫자는 이 행들을 더해서 만든다. 전체용 쿼리를 따로 돌리지 않는 이유는
 * 같은 조인을 두 번 타기 때문이고, 저장소마다 count를 도는 것보다도 나쁘기 때문이다.
 *
 * <p>PR이 하나도 없는 저장소도 0으로 나와야 한다. 그래서 {@code repositories}에서 시작하는
 * 왼쪽 조인이다 -- {@code pull_requests}에서 시작하면 빈 저장소가 목록에서 사라진다.
 */
public record RepositoryPullRequestCountRow(
        Long repositoryId,
        String fullName,
        Long pullRequestCount,
        Long failedCount,
        Long pendingCount,
        Long excludedCount,
        OffsetDateTime lastAnalyzedAt
) {

    public long pullRequests() {
        return pullRequestCount == null ? 0L : pullRequestCount;
    }

    public long failed() {
        return failedCount == null ? 0L : failedCount;
    }

    public long pending() {
        return pendingCount == null ? 0L : pendingCount;
    }

    public long excluded() {
        return excludedCount == null ? 0L : excludedCount;
    }
}

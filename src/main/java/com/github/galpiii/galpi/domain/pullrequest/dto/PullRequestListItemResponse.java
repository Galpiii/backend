package com.github.galpiii.galpi.domain.pullrequest.dto;

import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;

import java.time.OffsetDateTime;

/**
 * 목록 한 건.
 *
 * @param state    항상 {@code MERGED}다. 수집기가 병합된 PR만 가져오고
 *                 {@code pull_requests.merged_at}이 {@code NOT NULL}이라 다른 값이 될 수 없다.
 *                 컬럼이 아니라 서버 상수이며, OPEN PR 수집이 생기면 그때 실제 값이 된다
 * @param excluded 항상 {@code false}다. {@code pr_exclusions}는 테이블만 있고 쓰는 코드가
 *                 없다 -- 자동 노이즈 제외 규칙 자체가 아직 없다. 필드를 미리 두는 것은
 *                 제외 기능이 생겼을 때 응답 형태가 바뀌지 않게 하기 위해서다
 */
public record PullRequestListItemResponse(
        Long id,
        Integer number,
        String title,
        String state,
        OffsetDateTime mergedAt,
        String htmlUrl,
        PullRequestAuthorResponse author,
        PullRequestRepositoryResponse repository,
        PullRequestAnalysisResponse analysis,
        boolean excluded,
        DataCompleteness dataCompleteness
) {

    /** 수집 범위가 병합 PR로 고정되어 있는 동안 {@code state}가 가질 수 있는 유일한 값. */
    public static final String MERGED_STATE = "MERGED";

    public static PullRequestListItemResponse from(PullRequestListRow row) {
        return new PullRequestListItemResponse(
                row.id(),
                row.number(),
                row.title(),
                MERGED_STATE,
                row.mergedAt(),
                row.htmlUrl(),
                PullRequestAuthorResponse.of(row.authorLogin(), row.authorAvatarUrl()),
                new PullRequestRepositoryResponse(row.repositoryId(), row.repositoryFullName()),
                PullRequestAnalysisResponse.withoutSummary(row.analysisStatus(), row.changeType(),
                        row.analyzedAt(), row.analysisErrorCode()),
                row.exclusionCount() != null && row.exclusionCount() > 0,
                row.dataCompleteness());
    }
}

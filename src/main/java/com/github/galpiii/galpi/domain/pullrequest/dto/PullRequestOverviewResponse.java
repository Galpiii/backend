package com.github.galpiii.galpi.domain.pullrequest.dto;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * PR 목록 화면 머리말의 숫자들.
 *
 * <p>목록과 분리한 엔드포인트다. 헤더 숫자는 필터와 무관한 전체 기준이고, 저장소·작성자
 * 필터를 바꿀 때마다 전체 집계를 다시 돌 이유가 없다.
 *
 * @param excludedCount  항상 0이다. {@code pr_exclusions}에 쓰는 코드가 아직 없다.
 *                       필드를 미리 두는 것은 제외 기능이 생겼을 때 응답 형태가 바뀌지 않게
 *                       하기 위해서다
 * @param lastAnalyzedAt 이 프로젝트에서 마지막으로 끝난 요약의 시각. 요약이 하나도 끝나지
 *                       않았으면 {@code null}이다
 */
public record PullRequestOverviewResponse(
        long totalCount,
        long failedCount,
        long excludedCount,
        long pendingCount,
        OffsetDateTime lastAnalyzedAt,
        List<RepositoryOverview> repositories,
        Criteria criteria
) {

    public static PullRequestOverviewResponse from(List<RepositoryPullRequestCountRow> rows) {
        return new PullRequestOverviewResponse(
                rows.stream().mapToLong(RepositoryPullRequestCountRow::pullRequests).sum(),
                rows.stream().mapToLong(RepositoryPullRequestCountRow::failed).sum(),
                rows.stream().mapToLong(RepositoryPullRequestCountRow::excluded).sum(),
                rows.stream().mapToLong(RepositoryPullRequestCountRow::pending).sum(),
                rows.stream()
                        .map(RepositoryPullRequestCountRow::lastAnalyzedAt)
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null),
                rows.stream()
                        .map(row -> new RepositoryOverview(row.repositoryId(), row.fullName(),
                                row.pullRequests(), row.failed()))
                        .toList(),
                Criteria.fixed());
    }

    /** 저장소별 배지. 화면의 "PR 16개"가 이 값이다. */
    public record RepositoryOverview(Long id, String fullName, long pullRequestCount,
                                     long failedCount) {
    }

    /**
     * 화면의 "기준 · Merge됨 PR · 각 저장소 기본 브랜치 · 전체 기간" 문구용.
     *
     * <p><b>지금은 셋 다 고정값이다.</b> {@code analysis_configs}에 쓰는 경로가 없어 사용자가
     * 수집 기준을 바꿀 방법이 없고, 수집기는 언제나 각 저장소 기본 브랜치의 병합 PR을 기간
     * 제한 없이 가져온다. 이 필드를 지금 두는 것은 설정 화면이 생겼을 때 프론트가 문구를
     * 하드코딩해 두지 않게 하기 위해서다.
     */
    public record Criteria(String state, String baseBranch, String period) {

        public static Criteria fixed() {
            return new Criteria(PullRequestListItemResponse.MERGED_STATE, "DEFAULT", "ALL");
        }
    }
}

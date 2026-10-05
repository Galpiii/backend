package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureEvidenceStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

public record FeatureMatchResultsResponse(@Schema(description = "현재 표시 결과의 기준 FULL 실행 ID") long featureMatchRunId,
                                          @Schema(description = "기준 FULL 실행의 종료 상태. 최신 PARTIAL 실행 상태는 latest API에서 조회")
                                          FeatureMatchRunStatus status,
                                          @Schema(description = "기준 FULL 실행의 종료 시각") OffsetDateTime completedAt,
                                          Summary summary,
                                          List<RepositorySummary> repositories, List<Section> sections,
                                          FeatureMatchChangesResponse changes) {
    /**
     * 요약은 검색/저장소/목록 필터와 무관한 현재 프로젝트 기준 집계다.
     * 표시 결과·PR 분석 상태·마지막 대조 시도는 서로 다른 차원이므로 건수를 합산하지 않는다.
     */
    @Schema(name = "FeatureMatchResultSummary", description = "표시 결과, 현재 PR 분석 상태, 마지막 대조 시도 집계. 서로 겹칠 수 있어 합산하지 않음")
    public record Summary(int totalFeatureCount, long evidenceFoundFeatureCount, long attentionRequiredFeatureCount,
                          long noEvidenceFeatureCount, long unreviewedFeatureCount,
                          @Schema(description = "현재 PR 분석이 완료된 PR 수. 표시 결과 건수와 합산하지 않음")
                          int eligiblePullRequestCount,
                          @Schema(description = "현재 화면에 연결 근거가 표시되는 PR 수. 분석이 나중에 실패해도 이전 연결을 유지하면 포함")
                          long matchedPullRequestCount,
                          @Schema(description = "이전에 대조를 완료했고 현재 표시 연결이 없는 PR 수")
                          long unmatchedPullRequestCount,
                          @Schema(description = "현재 분석 완료 PR 중 마지막 종료된 기능대조 시도가 실패한 PR 수. 표시 결과와 겹칠 수 있음")
                          int matchingFailedPullRequestCount,
                          @Schema(description = "현재 분석 완료 PR 중 마지막 종료된 기능대조 시도가 취소된 PR 수. 표시 결과와 겹칠 수 있음")
                          int matchingCancelledPullRequestCount,
                          @Schema(description = "현재 PR 분석이 실패하여 기능대조 대상에서 제외된 PR 수")
                          int excludedFailedPullRequestCount,
                          @Schema(description = "현재 PR 분석이 취소되어 기능대조 대상에서 제외된 PR 수")
                          int excludedCancelledPullRequestCount,
                          long manualMatchCount, long manualOnlyPullRequestCount) {
    }

    @Schema(name = "FeatureMatchResultRepositorySummary")
    public record RepositorySummary(long repositoryId, String fullName, long matchedPullRequestCount) {
    }

    @Schema(name = "FeatureMatchResultSection")
    public record Section(Long sectionId, String title, Integer displayOrder, List<Feature> features) {
    }

    @Schema(name = "FeatureMatchResultFeature")
    public record Feature(long featureId, String name, FeatureReviewStatus reviewStatus,
                          @Schema(description = "기능별 PR 근거 상태. EVIDENCE_FOUND는 구현 완료를 의미하지 않습니다")
                          FeatureEvidenceStatus evidenceStatus,
                          Integer sourcePageStart, Integer sourcePageEnd, long requirementCount,
                          long relatedPullRequestCount, long manualMatchCount) {
    }
}

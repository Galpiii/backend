package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureEvidenceStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

public record FeatureMatchResultsResponse(long featureMatchRunId, @Schema(description = "대조 실행 결과 상태: COMPLETED 또는 PARTIALLY_COMPLETED")
                                          FeatureMatchRunStatus status,
                                          OffsetDateTime completedAt, Summary summary,
                                          List<RepositorySummary> repositories, List<Section> sections) {
    /**
     * 요약은 검색/저장소/목록 필터와 무관한 실행 전체 집계다.
     */
    @Schema(name = "FeatureMatchResultSummary")
    public record Summary(int totalFeatureCount, long evidenceFoundFeatureCount, long attentionRequiredFeatureCount,
                          long noEvidenceFeatureCount, long unreviewedFeatureCount, int eligiblePullRequestCount,
                          long matchedPullRequestCount, long unmatchedPullRequestCount,
                          int matchingFailedPullRequestCount,
                          int matchingCancelledPullRequestCount, int excludedFailedPullRequestCount,
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

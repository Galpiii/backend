package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureEvidenceStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;

import java.time.OffsetDateTime;
import java.util.List;

public record FeatureMatchResultsResponse(long featureMatchRunId, FeatureMatchRunStatus status,
                                          OffsetDateTime completedAt, Summary summary,
                                          List<RepositorySummary> repositories, List<Section> sections) {
    /**
     * 요약은 검색/저장소/목록 필터와 무관한 실행 전체 집계다.
     */
    public record Summary(int totalFeatureCount, long evidenceFoundFeatureCount, long attentionRequiredFeatureCount,
                          long noEvidenceFeatureCount, long unreviewedFeatureCount, int eligiblePullRequestCount,
                          long matchedPullRequestCount, long unmatchedPullRequestCount,
                          int matchingFailedPullRequestCount,
                          int matchingCancelledPullRequestCount, int excludedFailedPullRequestCount,
                          int excludedCancelledPullRequestCount,
                          long manualMatchCount, long manualOnlyPullRequestCount) {
    }

    public record RepositorySummary(long repositoryId, String fullName, long matchedPullRequestCount) {
    }

    public record Section(Long sectionId, String title, Integer displayOrder, List<Feature> features) {
    }

    public record Feature(long featureId, String name, FeatureReviewStatus reviewStatus,
                          FeatureEvidenceStatus evidenceStatus,
                          Integer sourcePageStart, Integer sourcePageEnd, long requirementCount,
                          long relatedPullRequestCount, long manualMatchCount) {
    }
}

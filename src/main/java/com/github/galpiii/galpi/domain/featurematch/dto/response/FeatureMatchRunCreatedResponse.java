package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunType;

import java.time.OffsetDateTime;

public record FeatureMatchRunCreatedResponse(long featureMatchRunId, FeatureMatchRunStatus status,
                                             long specDocumentId, int featureCount, int unreviewedFeatureCount,
                                             int eligiblePullRequestCount,
                                             int excludedFailedPullRequestCount, int excludedCancelledPullRequestCount,
                                             OffsetDateTime createdAt, FeatureMatchRunType runType, Long baseRunId) {
    public FeatureMatchRunCreatedResponse(long featureMatchRunId, FeatureMatchRunStatus status,
                                          long specDocumentId, int featureCount, int unreviewedFeatureCount,
                                          int eligiblePullRequestCount, int excludedFailedPullRequestCount,
                                          int excludedCancelledPullRequestCount, OffsetDateTime createdAt) {
        this(featureMatchRunId, status, specDocumentId, featureCount, unreviewedFeatureCount,
                eligiblePullRequestCount, excludedFailedPullRequestCount,
                excludedCancelledPullRequestCount, createdAt, FeatureMatchRunType.FULL, null);
    }
}

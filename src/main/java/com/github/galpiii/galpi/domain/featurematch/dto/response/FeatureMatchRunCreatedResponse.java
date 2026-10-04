package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;

import java.time.OffsetDateTime;

public record FeatureMatchRunCreatedResponse(long featureMatchRunId, FeatureMatchRunStatus status,
                                             long specDocumentId, int featureCount, int unreviewedFeatureCount,
                                             int eligiblePullRequestCount,
                                             int excludedFailedPullRequestCount, int excludedCancelledPullRequestCount,
                                             OffsetDateTime createdAt) {
}

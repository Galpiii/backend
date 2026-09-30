package com.github.galpiii.galpi.domain.featurematch.dto;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchFailureCode;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;

import java.time.OffsetDateTime;

/**
 * 기능대조 조회에 필요한 값만 담는 불변 투영.
 */
public record FeatureMatchRunRow(long id, long projectId, long specDocumentId, long userId,
                                 FeatureMatchRunStatus status, String featureSnapshotHash, int featureCount,
                                 int eligiblePrCount, int excludedFailedPrCount, int excludedCancelledPrCount,
                                 FeatureMatchFailureCode failureCode, OffsetDateTime startedAt,
                                 OffsetDateTime finishedAt, OffsetDateTime createdAt) {
}


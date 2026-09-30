package com.github.galpiii.galpi.domain.featurematch.dto;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchSource;

import java.time.OffsetDateTime;

/**
 * 기능대조 조회에 필요한 값만 담는 불변 투영.
 */
public record FeaturePrMatchRow(long id, long featureId, long pullRequestId, FeatureMatchSource source,
                                String reason, Long userId, OffsetDateTime createdAt) {
}


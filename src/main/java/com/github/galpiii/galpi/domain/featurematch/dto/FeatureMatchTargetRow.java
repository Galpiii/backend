package com.github.galpiii.galpi.domain.featurematch.dto;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchTargetStatus;


/**
 * 기능대조 조회에 필요한 값만 담는 불변 투영.
 */
public record FeatureMatchTargetRow(long id, long featureMatchRunId, long pullRequestAnalysisId,
                                    String analysisHeadSha, String analysisSnapshotHash,
                                    FeatureMatchTargetStatus status,
                                    String claimedBy, int attempts) {
}


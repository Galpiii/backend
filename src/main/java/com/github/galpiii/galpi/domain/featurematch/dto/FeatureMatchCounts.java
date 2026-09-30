package com.github.galpiii.galpi.domain.featurematch.dto;


/**
 * 기능대조 조회에 필요한 값만 담는 불변 투영.
 */
public record FeatureMatchCounts(int pendingCount, int runningCount, int completedCount,
                                 int failedCount, int cancelledCount) {
}


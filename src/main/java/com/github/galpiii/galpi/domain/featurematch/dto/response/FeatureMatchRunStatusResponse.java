package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchFailureCode;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

public record FeatureMatchRunStatusResponse(long featureMatchRunId, FeatureMatchRunStatus status,
                                            int featureCount, int totalTargetCount, int pendingCount, int runningCount,
                                            int completedCount,
                                            int failedCount, int cancelledCount, int progressPercent,
                                            @Schema(description = "실행 대표 실패 원인. PR 실패는 최다 발생 코드, 동률이면 코드명 오름차순. 취소는 취소 원인")
                                            FeatureMatchFailureCode failureCode,
                                            OffsetDateTime startedAt, OffsetDateTime finishedAt,
                                            OffsetDateTime createdAt) {
}

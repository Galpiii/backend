package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchFailureCode;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

public record FeatureMatchRunStatusResponse(long featureMatchRunId,
                                            @Schema(description = "실행 상태. 일부 대상 완료 후 나머지가 취소되면 PARTIALLY_COMPLETED")
                                            FeatureMatchRunStatus status,
                                            @Schema(description = "실행 기준 명세서 ID") long specDocumentId,
                                            int featureCount, int totalTargetCount, int pendingCount, int runningCount,
                                            int completedCount,
                                            int failedCount, int cancelledCount, int progressPercent,
                                            @Schema(description = "실행 대표 실패 원인. PR 실패는 최다 발생 코드, 동률이면 코드명 오름차순. CANCELLED 또는 PARTIALLY_COMPLETED에도 연결 해제·동의 철회 등의 취소 원인이 포함될 수 있음")
                                            FeatureMatchFailureCode failureCode,
                                            OffsetDateTime startedAt, OffsetDateTime finishedAt,
                                            OffsetDateTime createdAt, FeatureMatchRunType runType, Long baseRunId) {
    public FeatureMatchRunStatusResponse(long featureMatchRunId, FeatureMatchRunStatus status, long specDocumentId,
                                         int featureCount, int totalTargetCount, int pendingCount, int runningCount,
                                         int completedCount, int failedCount, int cancelledCount, int progressPercent,
                                         FeatureMatchFailureCode failureCode, OffsetDateTime startedAt,
                                         OffsetDateTime finishedAt, OffsetDateTime createdAt) {
        this(featureMatchRunId, status, specDocumentId, featureCount, totalTargetCount, pendingCount,
                runningCount, completedCount, failedCount, cancelledCount, progressPercent, failureCode,
                startedAt, finishedAt, createdAt, FeatureMatchRunType.FULL, null);
    }
}

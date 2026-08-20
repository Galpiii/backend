package com.github.galpiii.galpi.domain.project.dto;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;

import java.time.OffsetDateTime;

/** 최근 분석 작업의 상태와 시각. 한 번도 돌린 적이 없으면 {@code null}로 내려간다. */
public record LastAnalysisResponse(
        Long analysisRunId,
        AnalysisRunStatus status,
        OffsetDateTime requestedAt,
        OffsetDateTime finishedAt
) {

    public static LastAnalysisResponse of(Long analysisRunId, AnalysisRunStatus status,
                                          OffsetDateTime requestedAt,
                                          OffsetDateTime finishedAt) {
        if (analysisRunId == null || status == null) {
            return null;
        }
        return new LastAnalysisResponse(analysisRunId, status, requestedAt, finishedAt);
    }
}

package com.github.galpiii.galpi.domain.project.dto;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.domain.project.entity.ProjectOnboardingStep;
import com.github.galpiii.galpi.domain.project.entity.ProjectStatus;

import java.time.OffsetDateTime;

/**
 * 목록 쿼리가 한 번에 읽어 오는 한 행. 응답 형태와 분리해 둔 것은 조인·집계 결과를 그대로
 * 담기 때문이다. 화면에 나가는 모양은 {@link ProjectSummaryResponse}가 정한다.
 */
public record ProjectSummaryRow(
        Long id,
        String name,
        ProjectStatus status,
        ProjectOnboardingStep onboardingStep,
        Long repositoryCount,
        Long activeSpecDocumentId,
        Long lastAnalysisRunId,
        AnalysisRunStatus lastAnalysisStatus,
        OffsetDateTime lastAnalysisRequestedAt,
        OffsetDateTime lastAnalysisFinishedAt,
        OffsetDateTime updatedAt
) {
}

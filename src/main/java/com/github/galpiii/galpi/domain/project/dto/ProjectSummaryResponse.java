package com.github.galpiii.galpi.domain.project.dto;

import com.github.galpiii.galpi.domain.project.entity.ProjectOnboardingStep;
import com.github.galpiii.galpi.domain.project.entity.ProjectStatus;

import java.time.OffsetDateTime;

/**
 * 목록 한 건.
 *
 * <p>{@code status}가 {@code DRAFT}면 "저장소 연결 필요" 배지를, {@code onboardingStep}은
 * 그 항목을 눌렀을 때 위저드 어느 단계로 보낼지를 정한다.
 */
public record ProjectSummaryResponse(
        Long id,
        String name,
        ProjectStatus status,
        ProjectOnboardingStep onboardingStep,
        long repositoryCount,
        boolean hasSpecDocument,
        LastAnalysisResponse lastAnalysis,
        OffsetDateTime updatedAt
) {

    public static ProjectSummaryResponse from(ProjectSummaryRow row) {
        return new ProjectSummaryResponse(
                row.id(),
                row.name(),
                row.status(),
                row.onboardingStep(),
                row.repositoryCount() == null ? 0L : row.repositoryCount(),
                row.activeSpecDocumentId() != null,
                LastAnalysisResponse.of(row.lastAnalysisRunId(), row.lastAnalysisStatus(),
                        row.lastAnalysisRequestedAt(), row.lastAnalysisFinishedAt()),
                row.updatedAt());
    }
}

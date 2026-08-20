package com.github.galpiii.galpi.domain.project.dto;

import com.github.galpiii.galpi.domain.project.entity.Project;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 프로젝트 상세.
 *
 * <p>연결된 저장소는 {@code accessStatus}를 달고 나간다. 권한을 잃은 저장소에 배지를 띄우는
 * 것이 이 필드의 용도다.
 */
public record ProjectDetailResponse(
        Long id,
        String name,
        String status,
        String onboardingStep,
        List<LinkedRepositoryResponse> repositories,
        SpecDocumentSummaryResponse specDocument,
        LastAnalysisResponse lastAnalysis,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static ProjectDetailResponse of(Project project,
                                           List<LinkedRepositoryResponse> repositories,
                                           SpecDocumentSummaryResponse specDocument,
                                           LastAnalysisResponse lastAnalysis) {
        return new ProjectDetailResponse(
                project.getId(),
                project.getName(),
                project.getStatus().name(),
                project.getOnboardingStep().name(),
                repositories,
                specDocument,
                lastAnalysis,
                project.getCreatedAt(),
                project.getUpdatedAt());
    }
}

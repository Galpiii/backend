package com.github.galpiii.galpi.domain.project.dto;

import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;

import java.time.OffsetDateTime;

/**
 * 프로젝트에 붙어 있는 활성 명세서.
 *
 * <p>{@code extractionStatus}가 기능 추출 진행 배너의 근거다. 추출 자체는 이 Phase의 범위가
 * 아니라 상태만 그대로 내려간다.
 */
public record SpecDocumentSummaryResponse(
        Long specDocumentId,
        String fileName,
        ExtractionStatus extractionStatus,
        OffsetDateTime uploadedAt
) {

    public static SpecDocumentSummaryResponse from(SpecDocument document) {
        return new SpecDocumentSummaryResponse(
                document.getId(),
                document.getFileName(),
                document.getExtractionStatus(),
                document.getCreatedAt());
    }
}

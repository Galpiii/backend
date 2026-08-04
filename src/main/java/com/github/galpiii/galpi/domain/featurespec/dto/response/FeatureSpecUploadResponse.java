package com.github.galpiii.galpi.domain.featurespec.dto.response;

import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;

public record FeatureSpecUploadResponse(
        Long specDocumentId,
        String fileName,
        ExtractionStatus extractionStatus
) {

    public static FeatureSpecUploadResponse from(SpecDocument specDocument) {
        return new FeatureSpecUploadResponse(
                specDocument.getId(),
                specDocument.getFileName(),
                specDocument.getExtractionStatus()
        );
    }
}

package com.github.galpiii.galpi.domain.featurespec.dto.response;

import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionFailureCode;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;

public record FeatureSpecStatusResponse(
        Long specDocumentId,
        ExtractionStatus extractionStatus,
        ExtractionFailureCode failureCode
) {

    public static FeatureSpecStatusResponse from(SpecDocument specDocument) {
        return new FeatureSpecStatusResponse(
                specDocument.getId(),
                specDocument.getExtractionStatus(),
                specDocument.getFailureCode()
        );
    }
}

package com.github.galpiii.galpi.domain.featurespec.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionFailureCode;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionStatus;
import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;

@JsonInclude(JsonInclude.Include.NON_NULL)
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

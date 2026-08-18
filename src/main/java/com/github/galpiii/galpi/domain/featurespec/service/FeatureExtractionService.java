package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.ai.FeatureSpecAiService;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import com.github.galpiii.galpi.domain.featurespec.config.FeatureExtractionAsyncConfig;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionFailureCode;
import com.github.galpiii.galpi.domain.featurespec.support.FeatureExtractionResultNormalizer;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureExtractionService {

    private final FeatureSpecAiService featureSpecAiService;
    private final FeatureExtractionResultNormalizer normalizer;
    private final FeatureExtractionWriter featureExtractionWriter;
    private final FeatureSpecFileValidator featureSpecFileValidator;

    @Async(FeatureExtractionAsyncConfig.EXECUTOR)
    public void extract(Long specDocumentId, File pdf) {
        try {
            featureExtractionWriter.markProcessing(specDocumentId);

            FeatureSpecExtractionResult extracted = featureSpecAiService.analyze(pdf);

            if (extracted.features().isEmpty()) {
                log.warn(
                        "[기능명세서 분석] 문서에서 기능을 추출하지 못했습니다. specDocumentId: {}",
                        specDocumentId
                );
                markFailed(specDocumentId, ExtractionFailureCode.NO_FEATURE_EXTRACTED);
                return;
            }

            featureExtractionWriter.saveResult(
                    specDocumentId,
                    normalizer.normalize(specDocumentId, extracted)
            );

            log.info(
                    "[기능명세서 분석] 분석 완료. specDocumentId: {}, 기능 수: {}",
                    specDocumentId,
                    extracted.features().size()
            );
        } catch (Exception e) {
            log.error("[기능명세서 분석] 분석에 실패했습니다. specDocumentId: {}", specDocumentId, e);
            markFailed(specDocumentId, ExtractionFailureCode.ANALYSIS_FAILED);
        } finally {
            featureSpecFileValidator.deleteTempFile(pdf);
        }
    }

    private void markFailed(Long specDocumentId, ExtractionFailureCode failureCode) {
        try {
            featureExtractionWriter.markFailed(specDocumentId, failureCode);
        } catch (RuntimeException e) {
            log.error(
                    "[기능명세서 분석] 실패 상태를 남기지 못했습니다. specDocumentId: {}",
                    specDocumentId,
                    e
            );
        }
    }
}

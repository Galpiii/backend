package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.ai.FeatureSpecAiService;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import com.github.galpiii.galpi.domain.featurespec.config.FeatureExtractionAsyncConfig;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionFailureCode;
import com.github.galpiii.galpi.domain.featurespec.support.FeatureExtractionResultNormalizer;
import com.github.galpiii.galpi.domain.featurespec.store.FeatureSpecTempFileStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureExtractionService {

    private final FeatureSpecAiService featureSpecAiService;
    private final FeatureExtractionResultNormalizer normalizer;
    private final FeatureExtractionWriter featureExtractionWriter;
    private final FeatureSpecTempFileStore tempFileStore;
    @Qualifier(FeatureExtractionAsyncConfig.EXECUTOR)
    private final ThreadPoolTaskExecutor featureExtractionExecutor;

    @Async(FeatureExtractionAsyncConfig.EXECUTOR)
    public void extract(Long specDocumentId, File pdf) {
        long startedAt = System.nanoTime();

        try {
            featureExtractionWriter.markProcessing(specDocumentId);

            FeatureSpecExtractionResult raw = featureSpecAiService.analyze(pdf);
            FeatureSpecExtractionResult extracted = normalizer.normalize(specDocumentId, raw);

            if (extracted.features().isEmpty()) {
                log.warn(
                        "[기능명세서 분석] 문서에서 기능을 추출하지 못했습니다. specDocumentId: {}, 소요 시간: {}ms",
                        specDocumentId,
                        elapsedMs(startedAt)
                );
                markFailed(specDocumentId, ExtractionFailureCode.NO_FEATURE_EXTRACTED);
                return;
            }

            featureExtractionWriter.saveResult(specDocumentId, extracted);

            log.info(
                    "[기능명세서 분석] 분석 완료. specDocumentId: {}, 기능 수: {}, 정규화로 버린 항목: {}, 소요 시간: {}ms",
                    specDocumentId,
                    extracted.features().size(),
                    normalizer.droppedCount(raw, extracted),
                    elapsedMs(startedAt)
            );
        } catch (Exception e) {
            log.error(
                    "[기능명세서 분석] 분석에 실패했습니다. specDocumentId: {}, 소요 시간: {}ms",
                    specDocumentId,
                    elapsedMs(startedAt),
                    e
            );
            markFailed(specDocumentId, ExtractionFailureCode.ANALYSIS_FAILED);
        } finally {
            tempFileStore.delete(pdf);
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
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

    // 분석을 더 받을 수 없는 상태인지 확인
    public boolean isBusy() {
        ThreadPoolExecutor pool = featureExtractionExecutor.getThreadPoolExecutor();

        return pool.getActiveCount() >= pool.getMaximumPoolSize()
                && pool.getQueue().remainingCapacity() == 0;
    }
}

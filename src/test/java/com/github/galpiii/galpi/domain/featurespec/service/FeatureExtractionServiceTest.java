package com.github.galpiii.galpi.domain.featurespec.service;

import com.github.galpiii.galpi.ai.FeatureSpecAiService;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Requirement;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Section;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult.Source;
import com.github.galpiii.galpi.ai.exception.FeatureSpecAiException;
import com.github.galpiii.galpi.domain.featurespec.entity.ExtractionFailureCode;
import com.github.galpiii.galpi.domain.featurespec.support.FeatureExtractionResultNormalizer;
import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecTempFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureExtractionService — 비동기 추출 흐름")
class FeatureExtractionServiceTest {

    private static final long SPEC_DOCUMENT_ID = 1L;

    @Mock
    private FeatureSpecAiService featureSpecAiService;
    @Mock
    private FeatureExtractionWriter featureExtractionWriter;
    @Mock
    private FeatureSpecTempFileStore tempFileStore;

    private FeatureExtractionService service;
    private File pdf;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        service = new FeatureExtractionService(
                featureSpecAiService,
                new FeatureExtractionResultNormalizer(),
                featureExtractionWriter,
                tempFileStore,
                new ThreadPoolTaskExecutor()
        );

        pdf = Files.write(tempDir.resolve("spec.pdf"), "pdf".getBytes()).toFile();
    }

    private FeatureSpecExtractionResult resultWithFeature() {
        return new FeatureSpecExtractionResult(
                List.of(new Section("회원", "3. 회원", 1, 2)),
                List.of(new FeatureSpecExtractionResult.Feature(
                        "f1", "회원가입", "회원",
                        List.of(new Requirement("가입한다.", "원문")),
                        new Source(1, 1), List.of(), List.of(), null)));
    }

    @Test
    @DisplayName("분석에 성공하면 PROCESSING으로 바꾸고 결과를 저장한다")
    void savesResultOnSuccess() {
        given(featureSpecAiService.analyze(pdf)).willReturn(resultWithFeature());

        service.extract(SPEC_DOCUMENT_ID, pdf);

        verify(featureExtractionWriter).markProcessing(SPEC_DOCUMENT_ID);
        verify(featureExtractionWriter).saveResult(eqId(), any(FeatureSpecExtractionResult.class));
        verify(featureExtractionWriter, never()).markFailed(anyLong(), any());
    }

    @Test
    @DisplayName("기능을 하나도 추출하지 못하면 사유를 남기고 저장하지 않는다")
    void failsWhenNoFeatureExtracted() {
        given(featureSpecAiService.analyze(pdf))
                .willReturn(new FeatureSpecExtractionResult(List.of(), List.of()));

        service.extract(SPEC_DOCUMENT_ID, pdf);

        verify(featureExtractionWriter)
                .markFailed(SPEC_DOCUMENT_ID, ExtractionFailureCode.NO_FEATURE_EXTRACTED);
        verify(featureExtractionWriter, never()).saveResult(anyLong(), any());
    }

    @Test
    @DisplayName("OpenAI 호출이 최종 실패하면 분석 실패로 남긴다")
    void failsWhenAnalysisFails() {
        willThrow(new FeatureSpecAiException("최종 실패"))
                .given(featureSpecAiService).analyze(pdf);

        service.extract(SPEC_DOCUMENT_ID, pdf);

        verify(featureExtractionWriter)
                .markFailed(SPEC_DOCUMENT_ID, ExtractionFailureCode.ANALYSIS_FAILED);
    }

    @Test
    @DisplayName("결과 저장이 실패해도 분석 실패로 남긴다")
    void failsWhenSaveFails() {
        given(featureSpecAiService.analyze(pdf)).willReturn(resultWithFeature());
        willThrow(new DataIntegrityViolationException("constraint"))
                .given(featureExtractionWriter).saveResult(anyLong(), any());

        service.extract(SPEC_DOCUMENT_ID, pdf);

        verify(featureExtractionWriter)
                .markFailed(SPEC_DOCUMENT_ID, ExtractionFailureCode.ANALYSIS_FAILED);
    }

    @Test
    @DisplayName("성공하든 실패하든 임시 PDF를 지운다")
    void alwaysDeletesTempFile() {
        willThrow(new FeatureSpecAiException("최종 실패"))
                .given(featureSpecAiService).analyze(pdf);

        service.extract(SPEC_DOCUMENT_ID, pdf);

        verify(tempFileStore).delete(pdf);
    }

    /**
     * 비동기 스레드에서 예외를 던지면 아무도 잡지 않는다. 상태를 못 바꾼 문서는 다음 기동 때
     * StaleExtractionCleaner가 정리하므로 여기서는 삼키고 로그만 남긴다.
     */
    @Test
    @DisplayName("실패 표시까지 실패해도 예외를 밖으로 던지지 않는다")
    void swallowsFailureWhenMarkFailedFails() {
        willThrow(new FeatureSpecAiException("최종 실패"))
                .given(featureSpecAiService).analyze(pdf);
        willThrow(new DataIntegrityViolationException("db down"))
                .given(featureExtractionWriter).markFailed(anyLong(), any());

        service.extract(SPEC_DOCUMENT_ID, pdf);

        verify(tempFileStore).delete(pdf);
    }

    private long eqId() {
        return org.mockito.ArgumentMatchers.eq(SPEC_DOCUMENT_ID);
    }
}

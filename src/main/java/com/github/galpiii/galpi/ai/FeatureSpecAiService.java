package com.github.galpiii.galpi.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.galpiii.galpi.ai.config.OpenAiProperties;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import com.github.galpiii.galpi.ai.exception.FeatureSpecAiException;
import com.github.galpiii.galpi.ai.exception.RetryableAiException;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.RateLimitException;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FilePurpose;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputFile;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureSpecAiService {

    private static final long FILE_EXPIRES_AFTER_SECONDS = 3600L;
    private static final String EXPIRES_ANCHOR = "created_at";
    private static final String INPUT_TEXT = "첨부된 기능명세서 PDF를 분석하세요.";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OpenAIClient openAIClient;
    private final OpenAiProperties properties;
    private final FeatureSpecPrompt prompt;

    public FeatureSpecExtractionResult analyze(File pdf) {
        Instant deadline = Instant.now().plus(properties.analysisBudget());
        String fileId = uploadPdf(pdf, deadline);

        try {
            return extract(fileId, deadline);
        } finally {
            deleteFile(fileId);
        }
    }

    // pdf를 OpenAI에 업로드
    private String uploadPdf(File pdf, Instant deadline) {
        return withRetry("Files API 업로드", deadline, () -> {
            try {
                return openAIClient.files().create(fileCreateParams(pdf)).id();
            } catch (RuntimeException e) {
                throw classify(e);
            }
        });
    }

    // 분석 요청 및 결과 반환
    private FeatureSpecExtractionResult extract(String fileId, Instant deadline) {
        return withRetry("Responses API 호출", deadline, () -> {
            Response response;

            try {
                response = openAIClient.responses().create(responseCreateParams(fileId));
            } catch (RuntimeException e) {
                throw classify(e);
            }

            verifyCompleted(response);
            logUsage(response);

            return parse(outputText(response));
        });
    }

    // OpenAI에 올린 pdf 삭제
    private void deleteFile(String fileId) {
        try {
            openAIClient.files().delete(fileId);
        } catch (RuntimeException e) {
            log.error("[기능명세서 분석] OpenAI 파일 삭제 실패. fileId: {}", fileId, e);
        }
    }


    // 헬퍼 메서드들

    private FileCreateParams fileCreateParams(File pdf) {
        return FileCreateParams.builder()
                .file(pdf.toPath())
                .purpose(FilePurpose.USER_DATA)
                .expiresAfter(FileCreateParams.ExpiresAfter.builder()
                        .anchor(JsonValue.from(EXPIRES_ANCHOR))
                        .seconds(FILE_EXPIRES_AFTER_SECONDS)
                        .build())
                .build();
    }

    private ResponseCreateParams responseCreateParams(String fileId) {
        ResponseInputItem input = ResponseInputItem.ofMessage(
                ResponseInputItem.Message.builder()
                        .role(ResponseInputItem.Message.Role.USER)
                        .content(List.of(
                                ResponseInputContent.ofInputFile(
                                        ResponseInputFile.builder().fileId(fileId).build()),
                                ResponseInputContent.ofInputText(
                                        ResponseInputText.builder().text(INPUT_TEXT).build())))
                        .build());

        return ResponseCreateParams.builder()
                .model(properties.model())
                .instructions(prompt.instructions())
                .maxOutputTokens(properties.maxOutputTokens())
                .store(false)
                .text(ResponseTextConfig.builder()
                        .format(prompt.schemaConfig())
                        .build())
                .input(ResponseCreateParams.Input.ofResponse(List.of(input)))
                .build();
    }

    private void verifyCompleted(Response response) {
        ResponseStatus status = response.status().orElse(ResponseStatus.COMPLETED);

        if (status.equals(ResponseStatus.COMPLETED)) {
            return;
        }

        if (status.equals(ResponseStatus.INCOMPLETE)) {
            Response.IncompleteDetails.Reason reason = response.incompleteDetails()
                    .flatMap(Response.IncompleteDetails::reason)
                    .orElse(null);

            log.error(
                    "[기능명세서 분석] 응답이 완결되지 않아 재시도하지 않습니다. reason: {}",
                    reason
            );
            throw new FeatureSpecAiException("응답이 완결되지 않았습니다. reason: " + reason);
        }

        throw new RetryableAiException("응답 상태가 완료가 아닙니다. status: " + status);
    }

    private void logUsage(Response response) {
        response.usage().ifPresent(usage -> log.info(
                "[기능명세서 분석] 토큰 사용량. input: {}, output: {}, total: {}",
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens()
        ));
    }

    private String outputText(Response response) {
        return response.output().stream()
                .filter(item -> item.isMessage())
                .flatMap(item -> item.asMessage().content().stream())
                .filter(content -> content.isOutputText())
                .map(content -> content.asOutputText().text())
                .findFirst()
                .orElseThrow(() -> new RetryableAiException("응답에 본문이 없습니다."));
    }

    private FeatureSpecExtractionResult parse(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, FeatureSpecExtractionResult.class);
        } catch (JsonProcessingException e) {
            throw new RetryableAiException("응답을 스키마대로 해석하지 못했습니다.", e);
        }
    }

    private RuntimeException classify(RuntimeException e) {
        if (e instanceof RateLimitException
                || e instanceof InternalServerException
                || e instanceof OpenAIIoException
                || e instanceof OpenAIRetryableException) {
            return new RetryableAiException("OpenAI 호출에 실패했습니다.", e);
        }

        return new FeatureSpecAiException("OpenAI 호출에 실패했습니다.", e);
    }

    private <T> T withRetry(String operation, Instant deadline, Supplier<T> action) {
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            if (Instant.now().isAfter(deadline)) {
                log.warn(
                        "[기능명세서 분석] 분석 예산이 끝나 {}를 더 시도하지 않습니다. attempt: {}/{}",
                        operation,
                        attempt,
                        properties.maxAttempts()
                );
                throw new FeatureSpecAiException(operation + "가 분석 예산 안에 끝나지 않았습니다.", lastFailure);
            }

            try {
                return action.get();
            } catch (RetryableAiException e) {
                lastFailure = e;
                log.warn(
                        "[기능명세서 분석] {} 실패. attempt: {}/{}, reason: {}",
                        operation,
                        attempt,
                        properties.maxAttempts(),
                        e.getMessage()
                );

                if (attempt < properties.maxAttempts()) {
                    sleepBeforeRetry(attempt);
                }
            }
        }

        throw new FeatureSpecAiException(operation + "에 최종 실패했습니다.", lastFailure);
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(properties.retryBackoff().toMillis() << (attempt - 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeatureSpecAiException("분석 대기 중 인터럽트되었습니다.", e);
        }
    }
}

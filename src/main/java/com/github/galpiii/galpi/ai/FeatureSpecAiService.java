package com.github.galpiii.galpi.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.galpiii.galpi.ai.config.OpenAiProperties;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import com.github.galpiii.galpi.ai.exception.FeatureSpecAiException;
import com.github.galpiii.galpi.ai.exception.RetryableAiException;
import com.github.galpiii.galpi.ai.support.AiRetryTemplate;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
public class FeatureSpecAiService {

    private static final long FILE_EXPIRES_AFTER_SECONDS = 3600L;
    private static final String EXPIRES_ANCHOR = "created_at";
    private static final String INPUT_TEXT = "첨부된 기능명세서 PDF를 분석하세요.";

    // 스키마에 필드가 늘고 record 반영이 늦어도 비싼 호출을 재시도까지 돌며 실패하지 않도록 둔다.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String LOG_TAG = "[기능명세서 분석]";

    private final OpenAIClient openAIClient;
    private final OpenAiProperties properties;
    private final FeatureSpecPrompt prompt;
    private final AiRetryTemplate retries;

    public FeatureSpecAiService(OpenAIClient openAIClient, OpenAiProperties properties,
                                FeatureSpecPrompt prompt) {
        this.openAIClient = openAIClient;
        this.properties = properties;
        this.prompt = prompt;
        // 실패 갈래는 여기서 쓰지 않는다. 업로드 화면은 "분석에 실패했다" 하나로 처리하고
        // 갈래를 나눌 자리가 없다 -- 워커가 받는 PR 요약 쪽과 다른 점이다.
        this.retries = new AiRetryTemplate(LOG_TAG, properties.maxAttempts(),
                properties.retryBackoff(),
                (kind, message, cause) -> new FeatureSpecAiException(message, cause));
    }

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
        return retries.execute("Files API 업로드", deadline, () -> {
            try {
                return openAIClient.files().create(fileCreateParams(pdf)).id();
            } catch (RuntimeException e) {
                throw retries.classify(e);
            }
        });
    }

    // 분석 요청 및 결과 반환
    private FeatureSpecExtractionResult extract(String fileId, Instant deadline) {
        return retries.execute("Responses API 호출", deadline, () -> {
            Response response;

            try {
                response = openAIClient.responses().create(responseCreateParams(fileId));
            } catch (RuntimeException e) {
                throw retries.classify(e);
            }

            // 잘린 응답이 토큰을 가장 많이 쓰므로 완결 여부를 따지기 전에 남긴다.
            logUsage(response);
            verifyCompleted(response);

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
}

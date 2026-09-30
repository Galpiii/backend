package com.github.galpiii.galpi.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.galpiii.galpi.ai.config.OpenAiProperties;
import com.github.galpiii.galpi.ai.dto.FeatureMatchingResult;
import com.github.galpiii.galpi.ai.exception.FeatureMatchingAiException;
import com.github.galpiii.galpi.ai.exception.FeatureMatchingInvalidResponseException;
import com.github.galpiii.galpi.ai.exception.RetryableAiException;
import com.github.galpiii.galpi.ai.support.AiFailureKind;
import com.github.galpiii.galpi.ai.support.AiRetryTemplate;
import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * PR 한 건을 대조한다. 재시도는 DB 큐만 관리하여 SDK 호출 횟수가 곱해지지 않게 한다.
 * 원문·SDK 오류 메시지는 기록하지 않고 종류와 토큰 사용량만 남긴다.
 */
@Slf4j
@Service
public class FeatureMatchingAiService {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final OpenAIClient client;
    private final OpenAiProperties.Matching properties;
    private final FeatureMatchingPrompt prompt;
    private final AiRetryTemplate failures;

    public FeatureMatchingAiService(OpenAIClient client, OpenAiProperties properties,
                                    FeatureMatchingPrompt prompt) {
        this.client = client;
        this.properties = properties.matching();
        this.prompt = prompt;
        this.failures = new AiRetryTemplate("[기능대조]", 1, properties.retryBackoff(),
                FeatureMatchingAiException::new);
    }

    public FeatureMatchingResult match(String input) {
        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(properties.model())
                .instructions(prompt.instructions())
                .input(input)
                .store(false)
                .maxOutputTokens(properties.maxOutputTokens())
                .text(ResponseTextConfig.builder().format(prompt.schemaConfig()).build())
                .build();
        Instant deadline = Instant.now().plus(properties.budget());
        Response response;
        try {
            response = client.responses().create(params,
                    RequestOptions.builder().timeout(properties.budget()).build());
        } catch (RuntimeException exception) {
            RuntimeException classified = failures.classify(exception);
            String code = exception instanceof OpenAIServiceException service ? service.code().orElse(null) : null;
            log.warn("[기능대조 AI] SDK 호출 실패 type={} code={}", exception.getClass().getSimpleName(), code);
            if (classified instanceof RetryableAiException && Instant.now().isAfter(deadline)) {
                throw new FeatureMatchingAiException(AiFailureKind.BUDGET_EXHAUSTED,
                        "기능대조 호출 예산을 초과했습니다.", exception);
            }
            throw classified;
        }
        response.usage().ifPresent(usage -> log.info(
                "[기능대조 AI] 토큰 사용량 input={} cachedTokens={} output={} total={}",
                usage.inputTokens(), usage.inputTokensDetails().cachedTokens(),
                usage.outputTokens(), usage.totalTokens()));
        ResponseStatus status = response.status().orElse(ResponseStatus.COMPLETED);
        if (status.equals(ResponseStatus.INCOMPLETE)) {
            String reason = response.incompleteDetails().flatMap(Response.IncompleteDetails::reason)
                    .map(Object::toString).orElse("unknown");
            log.warn("[기능대조 AI] 미완결 응답 reason={}", reason);
            throw new FeatureMatchingInvalidResponseException("INCOMPLETE_" + reason);
        }
        if (!status.equals(ResponseStatus.COMPLETED)) {
            throw new RetryableAiException("응답 상태가 완료가 아닙니다.");
        }
        String text = response.output().stream()
                .filter(ResponseOutputItem::isMessage)
                .flatMap(item -> item.asMessage().content().stream())
                .filter(ResponseOutputMessage.Content::isOutputText)
                .map(content -> content.asOutputText().text())
                .findFirst()
                .orElseThrow(() -> new FeatureMatchingInvalidResponseException("MISSING_OUTPUT"));
        try {
            return JSON.readValue(text, FeatureMatchingResult.class);
        } catch (JsonProcessingException exception) {
            throw new FeatureMatchingInvalidResponseException("INVALID_JSON");
        }
    }
}

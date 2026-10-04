package com.github.galpiii.galpi.ai;

import com.github.galpiii.galpi.ai.config.OpenAiProperties;
import com.github.galpiii.galpi.ai.exception.FeatureMatchingAiException;
import com.github.galpiii.galpi.ai.exception.FeatureMatchingInvalidResponseException;
import com.github.galpiii.galpi.ai.exception.RetryableAiException;
import com.github.galpiii.galpi.ai.support.AiFailureKind;
import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.errors.BadRequestException;
import com.openai.errors.OpenAIIoException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseStatus;
import com.openai.services.blocking.ResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("기능대조 AI — 외부 호출 없이 SDK 응답과 실패를 검증")
class FeatureMatchingAiServiceTest {
    private final OpenAIClient client = mock(OpenAIClient.class);
    private final ResponseService responses = mock(ResponseService.class);
    private FeatureMatchingAiService service;

    @BeforeEach
    void setup() {
        when(client.responses()).thenReturn(responses);
        service = service(Duration.ofMinutes(3));
    }

    private FeatureMatchingAiService service(Duration budget) {
        OpenAiProperties properties = mock(OpenAiProperties.class);
        when(properties.matching()).thenReturn(new OpenAiProperties.Matching("gpt-5-mini", 12000, budget));
        when(properties.retryBackoff()).thenReturn(Duration.ofSeconds(5));
        FeatureMatchingPrompt prompt = new FeatureMatchingPrompt();
        prompt.load();
        return new FeatureMatchingAiService(client, properties, prompt);
    }

    @Test
    @DisplayName("상태가 생략된 정상 JSON 응답도 완료로 취급한다")
    void acceptsMissingStatus() {
        Response response = response("{\"matches\":[]}");
        when(responses.create(any(ResponseCreateParams.class), any(RequestOptions.class))).thenReturn(response);
        assertThat(service.match("input").matches()).isEmpty();
    }

    @Test
    @DisplayName("잘못된 JSON은 원인 구분이 가능한 예외로 반환한다")
    void invalidJson() {
        Response response = response("not json");
        when(responses.create(any(ResponseCreateParams.class), any(RequestOptions.class))).thenReturn(response);
        assertThatThrownBy(() -> service.match("input"))
                .isInstanceOf(FeatureMatchingInvalidResponseException.class).hasMessageContaining("INVALID_JSON");
    }

    @Test
    @DisplayName("미완결 응답은 구체적인 이유를 보존한다")
    void incompleteReason() {
        Response response = mock(Response.class);
        Response.IncompleteDetails details = mock(Response.IncompleteDetails.class);
        when(details.reason()).thenReturn(Optional.of(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS));
        when(response.status()).thenReturn(Optional.of(ResponseStatus.INCOMPLETE));
        when(response.incompleteDetails()).thenReturn(Optional.of(details));
        when(responses.create(any(ResponseCreateParams.class), any(RequestOptions.class))).thenReturn(response);
        assertThatThrownBy(() -> service.match("input"))
                .isInstanceOf(FeatureMatchingInvalidResponseException.class).hasMessageContaining("max_output_tokens");
    }

    @Test
    @DisplayName("400 오류는 영구 오류로 분류하고 SDK를 재호출하지 않는다")
    void rejectsPermanentSdkFailure() {
        when(responses.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(mock(BadRequestException.class));
        assertThatThrownBy(() -> service.match("input"))
                .isInstanceOf(FeatureMatchingAiException.class)
                .extracting(exception -> ((FeatureMatchingAiException) exception).getKind())
                .isEqualTo(AiFailureKind.CALL_FAILED);
        verify(responses, times(1)).create(any(ResponseCreateParams.class), any(RequestOptions.class));
    }

    @Test
    @DisplayName("네트워크 오류는 재시도 가능 신호만 반환하며 내부에서 반복하지 않는다")
    void retriesNetworkThroughQueueOnly() {
        when(responses.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new OpenAIIoException("io", new IOException()));
        assertThatThrownBy(() -> service.match("input")).isInstanceOf(RetryableAiException.class);
        verify(responses, times(1)).create(any(ResponseCreateParams.class), any(RequestOptions.class));
    }

    @Test
    @DisplayName("호출 예산을 초과한 네트워크 오류는 시간 초과로 구분한다")
    void budgetExhausted() {
        service = service(Duration.ofMillis(1));
        when(responses.create(any(ResponseCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(20);
                    throw new OpenAIIoException("timeout", new IOException());
                });
        assertThatThrownBy(() -> service.match("input"))
                .isInstanceOf(FeatureMatchingAiException.class)
                .extracting(exception -> ((FeatureMatchingAiException) exception).getKind())
                .isEqualTo(AiFailureKind.BUDGET_EXHAUSTED);
    }

    private Response response(String json) {
        ResponseOutputText text = mock(ResponseOutputText.class);
        when(text.text()).thenReturn(json);
        ResponseOutputMessage.Content content = mock(ResponseOutputMessage.Content.class);
        when(content.isOutputText()).thenReturn(true);
        when(content.asOutputText()).thenReturn(text);
        ResponseOutputMessage message = mock(ResponseOutputMessage.class);
        when(message.content()).thenReturn(List.of(content));
        ResponseOutputItem item = mock(ResponseOutputItem.class);
        when(item.isMessage()).thenReturn(true);
        when(item.asMessage()).thenReturn(message);
        Response response = mock(Response.class);
        when(response.output()).thenReturn(List.of(item));
        return response;
    }
}

package com.github.galpiii.galpi.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.galpiii.galpi.ai.config.OpenAiProperties;
import com.github.galpiii.galpi.ai.dto.FeatureSpecExtractionResult;
import com.github.galpiii.galpi.ai.exception.FeatureSpecAiException;
import com.openai.client.OpenAIClient;
import com.openai.errors.BadRequestException;
import com.openai.errors.RateLimitException;
import com.openai.models.files.FileCreateParams;
import com.openai.models.files.FileObject;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseStatus;
import com.openai.services.blocking.FileService;
import com.openai.services.blocking.ResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("FeatureSpecAiService — OpenAI 분석 호출")
class FeatureSpecAiServiceTest {

    private static final String FILE_ID = "file-abc123";
    private static final String VALID_JSON = """
            {
              "sections": [
                { "title": "회원", "sourceTitle": "3. 회원", "pageStart": 1, "pageEnd": 2 }
              ],
              "features": [
                {
                  "extractionId": "f1",
                  "name": "회원가입",
                  "section": "회원",
                  "requirements": [
                    { "content": "이메일로 가입한다.", "originalText": "사용자는 이메일로 가입한다." }
                  ],
                  "source": { "pageStart": 1, "pageEnd": 1 },
                  "issues": [],
                  "duplicateCandidates": [],
                  "splitSuggestion": null
                }
              ]
            }
            """;

    private final OpenAIClient openAIClient = mock(OpenAIClient.class);
    private final FileService fileService = mock(FileService.class);
    private final ResponseService responseService = mock(ResponseService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private FeatureSpecAiService service;
    private FeatureSpecPrompt prompt;
    private File pdf;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        given(openAIClient.files()).willReturn(fileService);
        given(openAIClient.responses()).willReturn(responseService);

        pdf = Files.write(tempDir.resolve("spec.pdf"), "pdf".getBytes()).toFile();

        prompt = new FeatureSpecPrompt();
        prompt.load();

        service = new FeatureSpecAiService(openAIClient, properties(Duration.ofMinutes(5)), prompt);
    }

    private OpenAiProperties properties(Duration analysisBudget) {
        return new OpenAiProperties(
                "test-api-key", "gpt-5", 64000L, Duration.ofMinutes(5), 3,
                Duration.ofMillis(1), analysisBudget);
    }

    private void givenUploadSucceeds() {
        FileObject fileObject = mock(FileObject.class);
        given(fileObject.id()).willReturn(FILE_ID);
        given(fileService.create(any(FileCreateParams.class))).willReturn(fileObject);
    }

    private Response completedResponse(String json) {
        ResponseOutputText outputText = mock(ResponseOutputText.class);
        given(outputText.text()).willReturn(json);

        ResponseOutputMessage.Content content = mock(ResponseOutputMessage.Content.class);
        given(content.isOutputText()).willReturn(true);
        given(content.asOutputText()).willReturn(outputText);

        ResponseOutputMessage message = mock(ResponseOutputMessage.class);
        given(message.content()).willReturn(List.of(content));

        ResponseOutputItem item = mock(ResponseOutputItem.class);
        given(item.isMessage()).willReturn(true);
        given(item.asMessage()).willReturn(message);

        Response response = mock(Response.class);
        given(response.status()).willReturn(Optional.of(ResponseStatus.COMPLETED));
        given(response.output()).willReturn(List.of(item));

        return response;
    }

    private Response incompleteResponse(Response.IncompleteDetails.Reason reason) {
        Response.IncompleteDetails details = mock(Response.IncompleteDetails.class);
        given(details.reason()).willReturn(Optional.of(reason));

        Response response = mock(Response.class);
        given(response.status()).willReturn(Optional.of(ResponseStatus.INCOMPLETE));
        given(response.incompleteDetails()).willReturn(Optional.of(details));

        return response;
    }

    @Test
    @DisplayName("분석에 성공하면 추출 결과를 돌려준다")
    void returnsExtractionResult() {
        givenUploadSucceeds();
        Response response = completedResponse(VALID_JSON);
        given(responseService.create(any(ResponseCreateParams.class))).willReturn(response);

        FeatureSpecExtractionResult result = service.analyze(pdf);

        assertThat(result.sections()).hasSize(1);
        assertThat(result.features()).hasSize(1);
        assertThat(result.features().getFirst().name()).isEqualTo("회원가입");
    }

    @Test
    @DisplayName("성공하면 OpenAI 파일을 지운다")
    void deletesFileOnSuccess() {
        givenUploadSucceeds();
        Response response = completedResponse(VALID_JSON);
        given(responseService.create(any(ResponseCreateParams.class))).willReturn(response);

        service.analyze(pdf);

        verify(fileService).delete(FILE_ID);
    }

    @Test
    @DisplayName("실패로 끝나도 OpenAI 파일을 지운다 — 분석용으로만 올린 파일이다")
    void deletesFileOnFailure() {
        givenUploadSucceeds();
        willThrow(mock(BadRequestException.class))
                .given(responseService).create(any(ResponseCreateParams.class));

        assertThatThrownBy(() -> service.analyze(pdf))
                .isInstanceOf(FeatureSpecAiException.class);

        verify(fileService).delete(FILE_ID);
    }

    @Test
    @DisplayName("파일 삭제가 실패해도 분석 결과를 뒤집지 않는다")
    void keepsResultWhenFileDeletionFails() {
        givenUploadSucceeds();
        Response response = completedResponse(VALID_JSON);
        given(responseService.create(any(ResponseCreateParams.class))).willReturn(response);
        willThrow(new IllegalStateException("openai down")).given(fileService).delete(anyString());

        FeatureSpecExtractionResult result = service.analyze(pdf);

        assertThat(result.features()).hasSize(1);
    }

    @Test
    @DisplayName("일시적인 오류는 재시도하고, PDF는 다시 올리지 않는다")
    void retriesWithoutReuploadingPdf() {
        givenUploadSucceeds();
        Response response = completedResponse(VALID_JSON);
        given(responseService.create(any(ResponseCreateParams.class)))
                .willThrow(mock(RateLimitException.class))
                .willThrow(mock(RateLimitException.class))
                .willReturn(response);

        FeatureSpecExtractionResult result = service.analyze(pdf);

        assertThat(result.features()).hasSize(1);
        verify(responseService, times(3)).create(any(ResponseCreateParams.class));
        verify(fileService, times(1)).create(any(FileCreateParams.class));
    }

    @Test
    @DisplayName("정해진 횟수를 모두 쓰면 최종 실패한다")
    void failsAfterExhaustingAttempts() {
        givenUploadSucceeds();
        willThrow(mock(RateLimitException.class))
                .given(responseService).create(any(ResponseCreateParams.class));

        assertThatThrownBy(() -> service.analyze(pdf))
                .isInstanceOf(FeatureSpecAiException.class);

        verify(responseService, times(3)).create(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("400은 재시도하지 않는다 — 다시 보내도 같은 결과다")
    void doesNotRetryBadRequest() {
        givenUploadSucceeds();
        willThrow(mock(BadRequestException.class))
                .given(responseService).create(any(ResponseCreateParams.class));

        assertThatThrownBy(() -> service.analyze(pdf))
                .isInstanceOf(FeatureSpecAiException.class);

        verify(responseService, times(1)).create(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("업로드가 최종 실패하면 분석을 시작하지 않는다")
    void skipsAnalysisWhenUploadFails() {
        willThrow(mock(RateLimitException.class))
                .given(fileService).create(any(FileCreateParams.class));

        assertThatThrownBy(() -> service.analyze(pdf))
                .isInstanceOf(FeatureSpecAiException.class);

        verify(fileService, times(3)).create(any(FileCreateParams.class));
        verify(responseService, never()).create(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("응답이 잘리면 재시도하지 않는다 — 토큰 한도를 넘긴 문서는 다시 불러도 같은 자리에서 잘린다")
    void doesNotRetryWhenTruncated() {
        givenUploadSucceeds();
        Response truncated = incompleteResponse(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS);
        given(responseService.create(any(ResponseCreateParams.class))).willReturn(truncated);

        assertThatThrownBy(() -> service.analyze(pdf))
                .isInstanceOf(FeatureSpecAiException.class);

        verify(responseService, times(1)).create(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("재시도 로그에 감싼 문구가 아니라 실제 원인을 남긴다")
    void logsUnderlyingFailureReason(CapturedOutput output) {
        givenUploadSucceeds();
        Response response = completedResponse(VALID_JSON);
        given(responseService.create(any(ResponseCreateParams.class)))
                .willThrow(mock(RateLimitException.class))
                .willReturn(response);

        service.analyze(pdf);

        assertThat(output).contains("RateLimitException");
    }

    @Test
    @DisplayName("예산이 끝나면 재시도하지 않는다 — 한 건이 분석 슬롯을 붙잡는 시간을 묶는다")
    void stopsRetryingWhenBudgetIsSpent() {
        service = new FeatureSpecAiService(openAIClient, properties(Duration.ofMillis(20)), prompt);
        given(fileService.create(any(FileCreateParams.class))).willAnswer(invocation -> {
            Thread.sleep(40);
            throw mock(RateLimitException.class);
        });

        assertThatThrownBy(() -> service.analyze(pdf))
                .isInstanceOf(FeatureSpecAiException.class);

        verify(fileService, times(1)).create(any(FileCreateParams.class));
    }

    @Test
    @DisplayName("콘텐츠 정책에 걸리면 재시도하지 않는다 — 같은 문서는 항상 같은 자리에서 걸린다")
    void doesNotRetryContentFilter() {
        givenUploadSucceeds();
        Response filtered = incompleteResponse(Response.IncompleteDetails.Reason.CONTENT_FILTER);
        given(responseService.create(any(ResponseCreateParams.class))).willReturn(filtered);

        assertThatThrownBy(() -> service.analyze(pdf))
                .isInstanceOf(FeatureSpecAiException.class);

        verify(responseService, times(1)).create(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("스키마대로 읽을 수 없는 응답은 재시도한다 — 모델의 일시적인 출력 문제일 수 있다")
    void retriesWhenResponseIsNotParseable() {
        givenUploadSucceeds();
        Response broken = completedResponse("{ 깨진 JSON");
        Response response = completedResponse(VALID_JSON);
        given(responseService.create(any(ResponseCreateParams.class)))
                .willReturn(broken)
                .willReturn(response);

        FeatureSpecExtractionResult result = service.analyze(pdf);

        assertThat(result.features()).hasSize(1);
        verify(responseService, times(2)).create(any(ResponseCreateParams.class));
    }
}

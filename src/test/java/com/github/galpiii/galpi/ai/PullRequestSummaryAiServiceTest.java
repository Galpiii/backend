package com.github.galpiii.galpi.ai;

import com.github.galpiii.galpi.ai.config.OpenAiProperties;
import com.github.galpiii.galpi.ai.dto.PullRequestSummaryResult;
import com.github.galpiii.galpi.ai.exception.PullRequestSummaryAiException;
import com.github.galpiii.galpi.ai.exception.PullRequestSummaryInvalidResponseException;
import com.openai.client.OpenAIClient;
import com.openai.errors.BadRequestException;
import com.openai.errors.RateLimitException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseStatus;
import com.openai.services.blocking.ResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PR 요약 호출과 저장 전 검증.
 *
 * <p>검증 쪽이 이 테스트의 무게중심이다. 스키마를 {@code strict}로 걸어도 structured output이
 * 보장하는 것은 구조뿐이라, 길이와 형식은 애플리케이션이 막지 않으면 그대로 통과한다. 그 검사가
 * 빠지면 "요약"이라는 이름으로 diff 조각이 DB에 들어가고 화면에 나간다.
 */
@DisplayName("PullRequestSummaryAiService — PR 요약 호출")
class PullRequestSummaryAiServiceTest {

    private static final String INPUT = "저장소: sample-org/backend\nPR #42: feat: 회원가입";

    private final OpenAIClient openAIClient = mock(OpenAIClient.class);
    private final ResponseService responseService = mock(ResponseService.class);

    private PullRequestSummaryAiService service;

    @BeforeEach
    void setUp() {
        given(openAIClient.responses()).willReturn(responseService);

        PullRequestSummaryPrompt prompt = new PullRequestSummaryPrompt();
        prompt.load();

        service = new PullRequestSummaryAiService(openAIClient, properties(Duration.ofMinutes(3)),
                prompt);
    }

    @Test
    @DisplayName("정상 응답이면 요약과 변경 유형을 돌려준다")
    void returnsSummary() {
        givenResponse(json("이메일과 비밀번호를 검증해 사용자를 만드는 회원가입 처리를 추가했습니다.",
                "FEATURE"));

        PullRequestSummaryResult result = service.summarize(INPUT);

        assertThat(result.summary()).startsWith("이메일과 비밀번호를");
        assertThat(result.changeType()).isEqualTo("FEATURE");
    }

    @Test
    @DisplayName("요약에 쓴 모델을 알려준다 — 어떤 모델이 만든 결과인지 행에 남아야 한다")
    void exposesModel() {
        assertThat(service.model()).isEqualTo("gpt-5-mini");
    }

    @Nested
    @DisplayName("저장 규칙을 어긴 응답")
    class InvalidResponses {

        @Test
        @DisplayName("300자를 넘으면 자르지 않고 실패로 둔다")
        void rejectsTooLongSummary() {
            // 자르면 문장이 반쯤 끊긴 채 화면에 남고, 사용자는 그것을 요약의 품질로 읽는다.
            givenResponse(json("가".repeat(301), "FEATURE"));

            assertThatThrownBy(() -> service.summarize(INPUT))
                    .isInstanceOf(PullRequestSummaryInvalidResponseException.class)
                    .hasMessageContaining("300자");
        }

        @Test
        @DisplayName("300자 정확히는 통과한다")
        void acceptsExactlyMaxLength() {
            givenResponse(json("가".repeat(300), "FEATURE"));

            assertThat(service.summarize(INPUT).summary()).hasSize(300);
        }

        @Test
        @DisplayName("코드 블록이 섞이면 실패로 둔다")
        void rejectsCodeBlock() {
            givenResponse(json("다음을 추가했습니다.\\n```java\\nclass A {}\\n```", "FEATURE"));

            assertThatThrownBy(() -> service.summarize(INPUT))
                    .isInstanceOf(PullRequestSummaryInvalidResponseException.class)
                    .hasMessageContaining("코드 인용");
        }

        @Test
        @DisplayName("인라인 코드도 실패로 둔다 — 백틱 하나면 이미 코드 인용이다")
        void rejectsInlineCode() {
            givenResponse(json("`AuthController`를 추가했습니다.", "FEATURE"));

            assertThatThrownBy(() -> service.summarize(INPUT))
                    .isInstanceOf(PullRequestSummaryInvalidResponseException.class);
        }

        @Test
        @DisplayName("diff 줄이 섞이면 실패로 둔다")
        void rejectsDiffLine() {
            givenResponse(json("변경 내용입니다.\\n+ public void save() {}", "FEATURE"));

            assertThatThrownBy(() -> service.summarize(INPUT))
                    .isInstanceOf(PullRequestSummaryInvalidResponseException.class)
                    .hasMessageContaining("diff");
        }

        @Test
        @DisplayName("문장 안의 빼기 기호는 막지 않는다 — 줄 시작만 본다")
        void allowsMinusInsideSentence() {
            givenResponse(json("A - B 순서로 처리하도록 바꿨습니다.", "REFACTOR"));

            assertThat(service.summarize(INPUT).changeType()).isEqualTo("REFACTOR");
        }

        @Test
        @DisplayName("빈 요약이면 실패로 둔다")
        void rejectsEmptySummary() {
            givenResponse(json("   ", "FEATURE"));

            assertThatThrownBy(() -> service.summarize(INPUT))
                    .isInstanceOf(PullRequestSummaryInvalidResponseException.class)
                    .hasMessageContaining("비어");
        }

        @Test
        @DisplayName("변경 유형이 비면 실패로 둔다")
        void rejectsEmptyChangeType() {
            givenResponse(json("회원가입 처리를 추가했습니다.", ""));

            assertThatThrownBy(() -> service.summarize(INPUT))
                    .isInstanceOf(PullRequestSummaryInvalidResponseException.class);
        }

        @Test
        @DisplayName("검증 실패는 재시도하지 않는다 — 같은 입력으로 다시 불러도 달라질 이유가 없다")
        void doesNotRetryInvalidResponse() {
            givenResponse(json("가".repeat(301), "FEATURE"));

            assertThatThrownBy(() -> service.summarize(INPUT))
                    .isInstanceOf(PullRequestSummaryInvalidResponseException.class);

            verify(responseService, times(1)).create(any(ResponseCreateParams.class));
        }
    }

    @Nested
    @DisplayName("호출 실패")
    class CallFailures {

        @Test
        @DisplayName("429는 한 번만 호출하고 durable worker에 재시도를 넘긴다")
        void delegatesRateLimitRetryToWorker() {
            given(responseService.create(any(ResponseCreateParams.class)))
                    .willThrow(mock(RateLimitException.class));

            assertThatThrownBy(() -> service.summarize(INPUT))
                    .isInstanceOf(PullRequestSummaryAiException.class);

            verify(responseService, times(1)).create(any(ResponseCreateParams.class));
        }

        @Test
        @DisplayName("400은 재시도하지 않는다 — 같은 요청은 항상 같은 답이 온다")
        void doesNotRetryBadRequest() {
            given(responseService.create(any(ResponseCreateParams.class)))
                    .willThrow(mock(BadRequestException.class));

            assertThatThrownBy(() -> service.summarize(INPUT))
                    .isInstanceOf(PullRequestSummaryAiException.class);

            verify(responseService, times(1)).create(any(ResponseCreateParams.class));
        }

        @Test
        @DisplayName("응답이 잘리면 재시도하지 않는다")
        void doesNotRetryTruncated() {
            Response truncated = incompleteResponse();
            given(responseService.create(any(ResponseCreateParams.class))).willReturn(truncated);

            assertThatThrownBy(() -> service.summarize(INPUT))
                    .isInstanceOf(PullRequestSummaryInvalidResponseException.class);

            verify(responseService, times(1)).create(any(ResponseCreateParams.class));
        }

        @Test
        @DisplayName("예산이 끝나면 더 시도하지 않는다 — 막힌 PR 하나가 워커 자리를 붙잡지 않게 한다")
        void stopsWhenBudgetIsSpent() {
            PullRequestSummaryPrompt prompt = new PullRequestSummaryPrompt();
            prompt.load();
            service = new PullRequestSummaryAiService(
                    openAIClient, properties(Duration.ofMillis(20)), prompt);
            given(responseService.create(any(ResponseCreateParams.class)))
                    .willAnswer(invocation -> {
                        Thread.sleep(40);
                        throw mock(RateLimitException.class);
                    });

            assertThatThrownBy(() -> service.summarize(INPUT))
                    .isInstanceOf(PullRequestSummaryAiException.class);

            verify(responseService, times(1)).create(any(ResponseCreateParams.class));
        }
    }

    private OpenAiProperties properties(Duration summaryBudget) {
        return new OpenAiProperties("test-api-key", "gpt-5", 64000L, Duration.ofMinutes(5), 3,
                Duration.ofMillis(1), Duration.ofMinutes(15),
                "gpt-5-mini", 2000L, summaryBudget);
    }

    private static String json(String summary, String changeType) {
        return """
                { "summary": "%s", "changeType": "%s" }
                """.formatted(summary, changeType);
    }

    private void givenResponse(String json) {
        // 응답 mock을 먼저 다 만들고 나서 스텁을 건다. given(...) 안에서 다른 given(...)을
        // 부르면 Mockito가 앞의 스텁을 미완성으로 보고 UnfinishedStubbingException을 던진다.
        Response response = completedResponse(json);
        given(responseService.create(any(ResponseCreateParams.class))).willReturn(response);
    }

    private static Response completedResponse(String json) {
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

    private static Response incompleteResponse() {
        Response.IncompleteDetails details = mock(Response.IncompleteDetails.class);
        given(details.reason())
                .willReturn(Optional.of(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS));

        Response response = mock(Response.class);
        given(response.status()).willReturn(Optional.of(ResponseStatus.INCOMPLETE));
        given(response.incompleteDetails()).willReturn(Optional.of(details));

        return response;
    }
}

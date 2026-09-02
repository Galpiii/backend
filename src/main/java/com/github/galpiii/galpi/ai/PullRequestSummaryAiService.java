package com.github.galpiii.galpi.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.galpiii.galpi.ai.config.OpenAiProperties;
import com.github.galpiii.galpi.ai.dto.PullRequestSummaryResult;
import com.github.galpiii.galpi.ai.exception.PullRequestSummaryAiException;
import com.github.galpiii.galpi.ai.exception.PullRequestSummaryInvalidResponseException;
import com.github.galpiii.galpi.ai.exception.RetryableAiException;
import com.github.galpiii.galpi.ai.support.AiRetryTemplate;
import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * PR 하나를 요약한다.
 *
 * <p>기능명세서 추출과 달리 Files API를 쓰지 않는다. 입력이 PDF가 아니라 조립된 텍스트라
 * 업로드·삭제 왕복이 필요 없고, 저장소당 수백 건이라 그 왕복이 그대로 곱해진다.
 *
 * <p><b>입력을 로그에 남기지 않는다.</b> 입력에는 PR 본문과 diff가 들어 있다. 남기는 것은
 * 토큰 수와 개수뿐이다.
 *
 * <p>{@code store(false)}로 부른다. OpenAI 쪽에 응답이 남지 않아야 비공개 저장소의 diff를
 * 보낸 흔적이 최소가 된다.
 */
@Slf4j
@Service
public class PullRequestSummaryAiService {

    private static final String LOG_TAG = "[PR 요약]";
    private static final String OPERATION = "Responses API 호출";

    /**
     * 내부에서 재시도하지 않는다. 요약은 DB 큐의 {@code attempts}가 전체 시도 횟수와
     * backoff를 관리하므로, 여기서도 재시도하면 실제 LLM 호출이 두 상한의 곱으로 늘어난다.
     *
     * <p>그래서 예산도 {@link AiRetryTemplate}에 맡기지 않는다 -- 저쪽 검사는 시도와 시도
     * 사이에 도는데, 시도가 한 번뿐이면 그 사이가 없다. 예산은 {@link #callOptions()}로
     * 호출 자체에 건다.
     */
    private static final int SINGLE_ATTEMPT = 1;

    /** 요약 본문 상한. 넘으면 자르지 않고 실패로 본다. */
    static final int MAX_SUMMARY_LENGTH = 300;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final OpenAIClient openAIClient;
    private final OpenAiProperties properties;
    private final PullRequestSummaryPrompt prompt;
    private final AiRetryTemplate retries;

    public PullRequestSummaryAiService(OpenAIClient openAIClient, OpenAiProperties properties,
                                       PullRequestSummaryPrompt prompt) {
        this.openAIClient = openAIClient;
        this.properties = properties;
        this.prompt = prompt;
        this.retries = new AiRetryTemplate(LOG_TAG, SINGLE_ATTEMPT,
                properties.retryBackoff(), PullRequestSummaryAiException::new);
    }

    /**
     * @param input 조립이 끝난 요약 입력. 마스킹과 절단은 호출부가 이미 했다
     * @throws PullRequestSummaryInvalidResponseException 응답이 저장 규칙을 어겼을 때
     * @throws PullRequestSummaryAiException              호출이 재시도로도 풀리지 않았을 때
     */
    public PullRequestSummaryResult summarize(String input) {
        Instant deadline = Instant.now().plus(properties.summaryBudget());

        PullRequestSummaryResult result = retries.execute(OPERATION, deadline, () -> {
            Response response;
            try {
                response = openAIClient.responses()
                        .create(responseCreateParams(input), callOptions());
            } catch (RuntimeException e) {
                throw retries.classify(e);
            }

            // 잘린 응답이 토큰을 가장 많이 쓰므로 완결 여부를 따지기 전에 남긴다.
            logUsage(response);
            verifyCompleted(response);

            return parse(outputText(response));
        });

        verifyStorable(result);
        return result;
    }

    /** 요약에 쓴 모델. 어떤 모델이 만든 결과인지 행에 남긴다. */
    public String model() {
        return properties.summaryModel();
    }

    /**
     * 요약 한 건의 예산을 호출 타임아웃으로 건다.
     *
     * <p>클라이언트 기본 타임아웃({@code galpi.openai.timeout})은 명세서 추출과 공유하는
     * 값이라 요약에는 너무 길다. 그대로 두면 막힌 PR 하나가 그 시간만큼 워커 자리와 배치
     * {@code join()}을 붙잡고, 선점 유효 기간({@code galpi.summary.worker.lease})과도
     * 맞지 않는다.
     */
    private RequestOptions callOptions() {
        return RequestOptions.builder()
                .timeout(properties.summaryBudget())
                .build();
    }

    private ResponseCreateParams responseCreateParams(String input) {
        return ResponseCreateParams.builder()
                .model(properties.summaryModel())
                .instructions(prompt.instructions())
                // 명세서 추출용 상한(기본 128,000)을 그대로 쓰면 폭주한 응답 하나가 요약
                // 수백 건 값을 태운다. gpt-5 계열은 reasoning 토큰이 출력에 과금된다.
                .maxOutputTokens(properties.summaryMaxOutputTokens())
                .store(false)
                .text(ResponseTextConfig.builder()
                        .format(prompt.schemaConfig())
                        .build())
                .input(input)
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

            // 출력 상한에 걸린 응답이다. 같은 입력으로 다시 걸어도 같은 곳에서 끊긴다.
            log.warn("{} 응답이 완결되지 않아 재시도하지 않습니다. reason: {}", LOG_TAG, reason);
            throw new PullRequestSummaryInvalidResponseException(
                    "응답이 완결되지 않았습니다. reason: " + reason);
        }

        throw new RetryableAiException("응답 상태가 완료가 아닙니다. status: " + status);
    }

    private void logUsage(Response response) {
        response.usage().ifPresent(usage -> log.info(
                "{} 토큰 사용량. input: {}, output: {}, total: {}",
                LOG_TAG, usage.inputTokens(), usage.outputTokens(), usage.totalTokens()));
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

    private PullRequestSummaryResult parse(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, PullRequestSummaryResult.class);
        } catch (JsonProcessingException e) {
            throw new RetryableAiException("응답을 스키마대로 해석하지 못했습니다.", e);
        }
    }

    /**
     * 저장 전 검증.
     *
     * <p>스키마를 {@code strict}로 걸어도 길이와 형식은 강제되지 않는다. structured output이
     * 보장하는 것은 구조이지 내용이 아니라, 이 검사가 없으면 "요약"이라는 이름으로 diff 조각이
     * 그대로 DB에 들어간다.
     *
     * <p>재시도하지 않고 바로 실패로 둔다 --
     * {@link PullRequestSummaryInvalidResponseException} 주석 참조.
     */
    private void verifyStorable(PullRequestSummaryResult result) {
        String summary = result.summary();

        if (summary == null || summary.isBlank()) {
            throw new PullRequestSummaryInvalidResponseException("요약이 비어 있습니다.");
        }
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            throw new PullRequestSummaryInvalidResponseException(
                    "요약이 " + MAX_SUMMARY_LENGTH + "자를 넘었습니다. length: " + summary.length());
        }
        if (summary.indexOf('`') >= 0) {
            // 백틱 하나만 있어도 막는다. 코드 블록과 인라인 코드를 구분해 봐야, 둘 다
            // 프롬프트가 금지한 "코드 인용"이라는 점은 같다.
            throw new PullRequestSummaryInvalidResponseException("요약에 코드 인용이 섞였습니다.");
        }
        if (containsDiffLine(summary)) {
            throw new PullRequestSummaryInvalidResponseException("요약에 diff 줄이 섞였습니다.");
        }
        if (result.changeType() == null || result.changeType().isBlank()) {
            throw new PullRequestSummaryInvalidResponseException("변경 유형이 비어 있습니다.");
        }
    }

    /**
     * {@code +}나 {@code -}로 시작하는 줄이 있는지.
     *
     * <p>줄 단위로 본다. 문장 안의 빼기 기호("A - B")까지 막으면 정상적인 요약이 걸린다.
     */
    private static boolean containsDiffLine(String summary) {
        for (String line : summary.split("\\R")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("+") || trimmed.startsWith("-")) {
                return true;
            }
        }
        return false;
    }
}

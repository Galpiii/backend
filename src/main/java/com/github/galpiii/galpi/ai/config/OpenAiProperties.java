package com.github.galpiii.galpi.ai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * OpenAI 호출 설정.
 *
 * <p>모델과 출력 상한이 용도별로 갈라져 있다. 기능명세서 추출은 문서 한 건에 한 번이라
 * 비싼 모델과 큰 출력 상한이 맞고, PR 요약은 저장소 하나에 수백 건이라 같은 값을 쓰면
 * 비용이 그대로 곱해진다.
 *
 * @param model                 기능명세서 추출 모델
 * @param maxOutputTokens       기능명세서 추출의 출력 상한. 기능 수십 개를 담은 JSON이 나온다
 * @param analysisBudget        기능명세서 분석 한 건이 재시도까지 포함해 쓸 수 있는 시간
 * @param summaryModel          PR 요약 모델.
 *                              <p>따로 두는 이유는 호출 건수다. 요약은 입력이 diff 몇만 자,
 *                              출력이 두세 문장이라 작은 모델로 충분하고, 저장소당 수백 건이라
 *                              모델 선택이 곧 비용이다
 * @param summaryMaxOutputTokens PR 요약의 출력 상한.
 *                              <p>요약 본문은 300자라 실제로 필요한 것은 수백 토큰이다.
 *                              여유를 크게 두는 이유는 gpt-5 계열이 reasoning 토큰을 출력에
 *                              함께 세기 때문이고, 그래도 상한을 두는 이유는 폭주한 응답
 *                              하나가 요약 수백 건 값을 태우지 않게 하기 위해서다
 * @param summaryBudget         PR 요약 한 건의 예산. 호출 타임아웃으로 그대로 걸리므로
 *                              요약 한 건의 실제 상한이다.
 *                              <p>{@code analysisBudget}을 쓰지 않는다. 저쪽은 PDF 한 건에
 *                              15분을 허용하는 값이라, 그대로 쓰면 막힌 PR 하나가 워커 자리를
 *                              15분씩 붙잡는다.
 *                              <p>{@code timeout}도 쓰지 않는다. 저쪽은 명세서 추출과 공유하는
 *                              클라이언트 기본값이라 요약에는 길고, 요약 워커의 lease와 맞지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "galpi.openai")
public record OpenAiProperties(
        @NotBlank String apiKey,
        @DefaultValue("gpt-5") String model,
        @DefaultValue("128000") @Min(1) long maxOutputTokens,
        @DefaultValue("10m") Duration timeout,
        @DefaultValue("3") @Min(1) @Max(10) int maxAttempts,
        @DefaultValue("1s") Duration retryBackoff,
        @DefaultValue("15m") Duration analysisBudget,
        @DefaultValue("gpt-5-mini") @NotBlank String summaryModel,
        @DefaultValue("2000") @Min(1) long summaryMaxOutputTokens,
        @DefaultValue("3m") Duration summaryBudget
) {
}

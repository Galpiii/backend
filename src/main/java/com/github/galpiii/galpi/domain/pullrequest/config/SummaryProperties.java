package com.github.galpiii.galpi.domain.pullrequest.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * PR 요약 워커와 입력 조립 설정.
 *
 * @param worker              폴링 워커 설정
 * @param maxPatchChars       PR 하나의 diff를 LLM 입력에 담는 총 상한.
 *                            <p>60,000자는 토큰으로 대략 20,000이다. 여기에 제목·본문·커밋·파일
 *                            목록이 더해져 입력 한 건이 30,000 토큰을 넘지 않게 잡은 값이다.
 *                            올리면 요약 품질이 조금 나아지고 비용이 그만큼 는다 -- 저장소당
 *                            수백 건이라 한 건의 차이가 그대로 곱해진다
 * @param maxPatchCharsPerFile 파일 하나가 가져갈 수 있는 diff 상한.
 *                            <p>이 값이 없으면 거대한 lock 파일 하나가 PR 전체 예산을 먹고
 *                            정작 구현 파일의 diff가 한 줄도 들어가지 못한다. 파일 사이에
 *                            예산을 고르게 나누는 것이 목적이라 총 상한보다 훨씬 작다
 * @param maxInputChars       제목·본문·커밋·파일 목록·diff를 모두 합친 최종 입력 상한.
 *                            {@code maxPatchChars}만으로는 긴 PR 본문이나 수천 개의 커밋이
 *                            모델 컨텍스트와 비용 상한을 우회할 수 있어 별도로 둔다
 * @param maxFilePages        요약 입력을 만들 때 받아 올 변경 파일 페이지 수(페이지당 100개).
 *                            <p>수집 쪽(기본 30페이지 = 3,000개)과 나눈다. 저쪽은 모든 파일을
 *                            행으로 남겨야 하지만, 요약은 파일 목록이 {@code maxInputChars / 6},
 *                            diff가 {@code maxPatchChars}에서 어차피 잘린다. 뒤쪽 페이지는
 *                            버려질 내용을 받으려고 GitHub 호출만 쓰는 셈이라, 큰 PR 몇 건이
 *                            동시에 돌면 rate limit 여유를 그만큼 빨리 깎는다
 */
@Validated
@ConfigurationProperties(prefix = "galpi.summary")
public record SummaryProperties(
        @DefaultValue @NotNull Worker worker,
        @DefaultValue("60000") @Min(0) int maxPatchChars,
        @DefaultValue("8000") @Min(0) int maxPatchCharsPerFile,
        @DefaultValue("90000") @Min(1) int maxInputChars,
        @DefaultValue("2") @Min(1) @Max(30) int maxFilePages
) {

    /**
     * @param enabled        워커를 띄울지. API만 서비스하는 인스턴스에서 false로 둔다
     * @param pollInterval   큐를 들여다보는 주기
     * @param lease          선점이 유효한 기간. 이보다 오래된 선점은 다른 워커가 가져갈 수 있다.
     *                       분석 작업(30분)보다 짧다 -- 요약 하나는 GitHub 호출 한 번과 LLM
     *                       호출 한 번이라 10분을 넘길 이유가 없고, 길게 잡으면 죽은 워커가
     *                       물고 있던 PR이 그만큼 늦게 풀린다
     * @param retryBackoff   일시 실패 후 다음 선점까지의 기본 대기 시간. 시도마다 지수적으로 늘어난다
     * @param maxAttempts    이 횟수를 넘게 시도된 요약은 더 돌리지 않고 FAILED로 끝낸다.
     *                       사용자가 재요약을 누르면 시도 횟수는 0으로 돌아간다
     * @param batchSize      한 번에 선점할 요약 수.
     *                       <p>분석 작업 워커가 하나씩 집는 것과 다르다. 저장소 수집은 한 건이
     *                       몇 분이라 폴링 주기가 묻히지만, 요약은 한 건이 몇 초라 하나씩 집으면
     *                       폴링 주기가 그대로 처리량 상한이 된다 -- 5초 주기면 PR 300개에
     *                       25분이 걸린다
     * @param maxConcurrency 배치 안에서 동시에 처리할 요약 수.
     *                       <p>기능명세서 추출 풀(기본 3)과 나누는 이유는 성격이 달라서다.
     *                       저쪽은 한 건이 수십 초짜리 문서 분석이고 이쪽은 짧은 요약 수백 건이라,
     *                       풀을 공유하면 사용자가 방금 올린 명세서가 요약 대기열 뒤에 선다
     */
    @Validated
    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") @NotNull Duration pollInterval,
            @DefaultValue("10m") @NotNull Duration lease,
            @DefaultValue("5s") @NotNull Duration retryBackoff,
            @DefaultValue("3") @Min(1) int maxAttempts,
            @DefaultValue("10") @Min(1) @Max(100) int batchSize,
            @DefaultValue("4") @Min(1) @Max(32) int maxConcurrency
    ) {
    }
}

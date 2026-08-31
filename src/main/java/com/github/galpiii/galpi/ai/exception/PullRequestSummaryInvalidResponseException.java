package com.github.galpiii.galpi.ai.exception;

/**
 * 응답은 왔지만 저장 규칙을 만족하지 못했다.
 *
 * <p>재시도하지 않는다. 스키마를 {@code strict}로 걸어 둔 상태에서 길이나 형식 규칙을 어겼다면
 * 모델이 명시적인 지시를 무시한 것이고, 같은 입력으로 다시 부른다고 달라질 이유가 없다.
 * PR 요약은 저장소당 수백 건이라 "혹시 되려나" 하고 다시 거는 비용이 그대로 곱해진다.
 *
 * <p>초과분을 잘라 저장하지도 않는다. 300자에서 자르면 문장이 반쯤 끊긴 채 화면에 남고,
 * 사용자는 그것을 요약의 품질로 읽는다. 실패로 두면 재요약이라는 정상적인 복구 경로가 있다.
 */
public class PullRequestSummaryInvalidResponseException extends RuntimeException {

    public PullRequestSummaryInvalidResponseException(String message) {
        super(message);
    }
}

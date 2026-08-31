package com.github.galpiii.galpi.ai.dto;

/**
 * PR 요약 응답.
 *
 * <p>{@code changeType}을 enum이 아니라 문자열로 받는다. 스키마에 enum을 걸어 뒀지만 모델이
 * 목록에 없는 값을 보내는 경우까지 파싱 실패로 만들면, 어디서 어긋났는지 알 수 없는 예외가
 * 난다. 문자열로 받아 검증 단계에서 판정하면 "응답이 규칙을 어겼다"는 사실이 실패 사유로
 * 그대로 남는다.
 */
public record PullRequestSummaryResult(String summary, String changeType) {
}

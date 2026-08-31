package com.github.galpiii.galpi.domain.pullrequest.dto;

/**
 * 재요약 요청 결과.
 *
 * <p>되돌릴 것이 하나도 없어도 에러가 아니다. 사용자가 화면을 보고 누른 시점과 워커가 마지막
 * 실패를 처리한 시점이 어긋나 있을 뿐이고, 그때 4xx를 주면 "이미 다 됐다"가 "실패했다"로
 * 보인다. {@code requeuedCount: 0}으로 그 사실만 알린다.
 */
public record PullRequestAnalysisRetryResponse(int requeuedCount) {
}

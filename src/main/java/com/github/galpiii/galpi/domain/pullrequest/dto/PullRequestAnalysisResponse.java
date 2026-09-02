package com.github.galpiii.galpi.domain.pullrequest.dto;

import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;

import java.time.OffsetDateTime;

/**
 * PR 하나의 AI 분석 결과.
 *
 * <p>{@code status}가 {@code COMPLETED}면 {@code summary}와 {@code changeType}이 채워지고
 * {@code errorCode}는 비어 있다. {@code FAILED}나 {@code CANCELLED}면 성공 결과는 비고
 * {@code errorCode}가 이유를 담는다. 둘이 동시에 차 있는 상태는 DB
 * CHECK 제약이 막는다.
 *
 * <p>목록에서는 {@code summary}가 항상 {@code null}이다. 본문은 상세에서만 준다.
 *
 * <p>{@code errorCode}는 사용자에게 나가는 {@code ErrorCode}와 다른 체계다. 요약 실패는
 * 요청 실패가 아니라 이 PR만의 상태라 HTTP 상태로 번역되지 않는다.
 */
public record PullRequestAnalysisResponse(
        PullRequestAnalysisStatus status,
        String summary,
        ChangeType changeType,
        OffsetDateTime analyzedAt,
        SummaryFailureCode errorCode
) {

    /** 목록용. 요약 본문을 뺀다. */
    public static PullRequestAnalysisResponse withoutSummary(
            PullRequestAnalysisStatus status, ChangeType changeType,
            OffsetDateTime analyzedAt, SummaryFailureCode errorCode) {
        if (status == null) {
            return null;
        }
        return new PullRequestAnalysisResponse(status, null, changeType, analyzedAt, errorCode);
    }

    /**
     * 상세용.
     *
     * <p>인계가 아직 돌지 않아 요약 행 자체가 없을 수 있다. 그때 {@code null}을 내려 프론트가
     * "분석 대기"와 "분석 실패"를 구분하게 한다.
     */
    public static PullRequestAnalysisResponse from(PullRequestAnalysis analysis) {
        if (analysis == null) {
            return null;
        }
        return new PullRequestAnalysisResponse(
                analysis.getStatus(),
                analysis.getSummary(),
                analysis.getChangeType(),
                analysis.getAnalyzedAt(),
                analysis.getErrorCode());
    }
}

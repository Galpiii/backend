package com.github.galpiii.galpi.domain.featurespec.dto.response;

/**
 * 검토 진행 상황 요약.
 *
 * <p>검토 화면의 탭 숫자와, 기능-PR 대조를 시작할 때 "아직 확인하지 않은 기능이 있습니다"를
 * 물을지 판단하는 데 쓴다. 아직 확인하지 않은 기능 수는 {@code total - reviewed}다.
 *
 * <p>세 값은 서로 겹치지 않고 합하면 {@code total}이 된다. 검토가 끝나면 특이사항을 지우므로
 * "특이사항이 있다"와 "검토가 끝났다"가 동시에 참일 수 없다.
 */
public record FeatureReviewSummaryResponse(
        long reviewRequired,
        long noIssue,
        long reviewed,
        long total
) {

    public static FeatureReviewSummaryResponse of(long total, long reviewRequired, long reviewed) {
        return new FeatureReviewSummaryResponse(
                reviewRequired,
                total - reviewRequired - reviewed,
                reviewed,
                total
        );
    }
}

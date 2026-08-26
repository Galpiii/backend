package com.github.galpiii.galpi.domain.featurespec.dto;

/**
 * 검토 화면의 탭.
 *
 * <p>값 이름을 {@code FeatureReviewStatus}와 겹치지 않게 둔다. 특히 "아직 확인하지 않음"은
 * 두 탭으로 갈리므로, 상태 이름을 그대로 쓰면 같은 단어가 두 뜻을 갖는다.
 *
 * <p>{@code REVIEW_REQUIRED}에 상태 조건이 없는 이유는, 검토가 끝나면 특이사항을 지우기
 * 때문이다. 특이사항이 남아 있다는 것 자체가 아직 확인하지 않았다는 뜻이다.
 */
public enum FeatureReviewFilter {

    /** 전체 */
    ALL,

    /** 확인 필요 — 특이사항이 있는 기능 */
    REVIEW_REQUIRED,

    /** 미확인 — 특이사항 없이 아직 확인하지 않은 기능 */
    NO_ISSUE,

    /** 검토 완료 — 그대로 승인했거나 직접 수정한 기능 */
    REVIEWED
}

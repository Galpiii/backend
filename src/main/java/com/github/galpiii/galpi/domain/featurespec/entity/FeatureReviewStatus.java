package com.github.galpiii.galpi.domain.featurespec.entity;

/**
 * 기능 하나에 대한 사용자 검토 상태.
 *
 * <p>특이사항({@link FeatureIssue}) 단위가 아니라 기능 단위다. 한 기능에 특이사항이 여럿
 * 붙어 있어도 사용자는 그것들을 함께 보고 행동 하나를 고르며, 그 행동으로 검토가 끝난다.
 *
 * <p>검토는 필수 단계가 아니다. {@code UNREVIEWED}가 남아 있어도 기능-PR 대조를 막지 않는다.
 */
public enum FeatureReviewStatus {

    // 사용자가 아직 확인하지 않음
    UNREVIEWED,

    // 사용자가 확인했고 추출 결과를 그대로 쓰기로 함
    USER_CONFIRMED,

    // 사용자 판단으로 내용이 바뀜. 병합·분리로 새로 만들어진 기능도 여기에 해당한다
    USER_MODIFIED
}

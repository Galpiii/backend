package com.github.galpiii.galpi.domain.featurematch.entity;

public enum FeatureMatchTargetStatus {
    /**
     * 대조 대기 또는 재시도 대기.
     */
    PENDING,

    /**
     * 워커가 선점하여 처리 중.
     */
    RUNNING,

    /**
     * 대조 완료.
     */
    COMPLETED,

    /**
     * 대조 실패.
     */
    FAILED,

    /**
     * 실행 취소로 처리하지 않음.
     */
    CANCELLED
}

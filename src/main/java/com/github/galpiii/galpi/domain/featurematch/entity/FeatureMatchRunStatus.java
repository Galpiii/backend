package com.github.galpiii.galpi.domain.featurematch.entity;

public enum FeatureMatchRunStatus {
    /**
     * 실행 대기.
     */
    QUEUED,

    /**
     * 대조 진행 중.
     */
    RUNNING,

    /**
     * 모든 대상 대조 완료.
     */
    COMPLETED,

    /**
     * 일부 대상 성공 및 일부 실패 또는 취소.
     */
    PARTIALLY_COMPLETED,

    /**
     * 성공 없이 대조 실패.
     */
    FAILED,

    /**
     * 실행 기준 변경 등으로 취소.
     */
    CANCELLED
}

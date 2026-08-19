package com.github.galpiii.galpi.domain.analysis.entity;

/** 분석 작업 안에서 저장소 하나의 상태. */
public enum AnalysisRunTargetStatus {

    PENDING,
    COLLECTING,
    COMPLETED,
    FAILED,

    /** rate limit이나 작업 중단으로 아예 손대지 못한 저장소. 실패와 구분한다. */
    SKIPPED
}

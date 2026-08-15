package com.github.galpiii.galpi.domain.featurespec.entity;

public enum ExtractionStatus {

    // 업로드는 끝났으나 아직 분석을 시작하지 않음
    PENDING,

    // 분석 진행 중
    PROCESSING,

    // 분석이 끝나 결과가 저장됨
    COMPLETED,

    // 분석 실패
    FAILED
}

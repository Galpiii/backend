package com.github.galpiii.galpi.domain.featurespec.entity;

// 분석이 최종 실패한 이유
public enum ExtractionFailureCode {

    // 문서에서 기능을 하나도 추출하지 못함
    NO_FEATURE_EXTRACTED,

    // 그 외 모든 실패 (서버 내부 오류)
    ANALYSIS_FAILED
}

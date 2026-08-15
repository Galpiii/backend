package com.github.galpiii.galpi.domain.featurespec.entity;

public enum FeatureIssueType {

    // 다른 기능과 중복 의심
    DUPLICATE_SUSPECTED,

    // 추출된 세부 요구사항 없음
    MISSING_REQUIREMENTS,

    // 기능 분리 권장
    SPLIT_RECOMMENDED,

    // 원문 근거가 약해 확인 필요
    SOURCE_REVIEW_REQUIRED,

    // 원문 내용끼리 서로 어긋남
    SOURCE_CONTENT_CONFLICT
}

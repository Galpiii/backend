package com.github.galpiii.galpi.domain.featurematch.dto;

public enum FeatureEvidenceStatus {
    /**
     * 관련 PR 근거를 찾음. 구현 완료를 의미하지 않음.
     */
    EVIDENCE_FOUND,

    /**
     * 관련 PR 근거를 찾지 못함. 미구현을 의미하지 않음.
     */
    NO_EVIDENCE
}

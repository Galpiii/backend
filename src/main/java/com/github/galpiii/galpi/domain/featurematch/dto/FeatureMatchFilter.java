package com.github.galpiii.galpi.domain.featurematch.dto;


public enum FeatureMatchFilter {
    /**
     * 모든 기능.
     */
    ALL,

    /**
     * 관련 PR이 있는 기능.
     */
    EVIDENCE_FOUND,

    /**
     * 관련 PR이 없거나 아직 검토하지 않은 기능.
     */
    ATTENTION_REQUIRED
}

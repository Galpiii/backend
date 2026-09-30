package com.github.galpiii.galpi.domain.featurematch.dto;

import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;


/**
 * 기능대조 조회에 필요한 값만 담는 불변 투영.
 */
public record FeatureMatchFeatureRow(long id, Long sectionId, String sectionTitle, Integer sectionOrder,
                                     String name, int displayOrder, FeatureReviewStatus reviewStatus,
                                     Integer sourcePageStart,
                                     Integer sourcePageEnd) {
}


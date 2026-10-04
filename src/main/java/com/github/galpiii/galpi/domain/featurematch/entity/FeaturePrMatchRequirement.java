package com.github.galpiii.galpi.domain.featurematch.entity;

import com.github.galpiii.galpi.domain.featurespec.entity.FeatureRequirement;
import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기능–PR 연결에서 근거로 사용한 세부 요구사항.
 */
@Entity
@Getter
@Table(name = "feature_pr_match_requirements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeaturePrMatchRequirement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_pr_match_id", nullable = false)
    private FeaturePrMatch featurePrMatch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_requirement_id", nullable = false)
    private FeatureRequirement featureRequirement;

    private FeaturePrMatchRequirement(FeaturePrMatch match, FeatureRequirement requirement) {
        this.featurePrMatch = match;
        this.featureRequirement = requirement;
    }

    public static FeaturePrMatchRequirement of(FeaturePrMatch match, FeatureRequirement requirement) {
        return new FeaturePrMatchRequirement(match, requirement);
    }
}

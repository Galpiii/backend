package com.github.galpiii.galpi.domain.featurespec.entity;

import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "split_suggestion_requirements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SplitSuggestionFeatureRequirement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "split_feature_suggestion_id", nullable = false)
    private SplitFeatureSuggestion splitFeatureSuggestion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_requirement_id", nullable = false)
    private FeatureRequirement featureRequirement;

    @Builder
    private SplitSuggestionFeatureRequirement(SplitFeatureSuggestion splitFeatureSuggestion,
                                              FeatureRequirement featureRequirement) {
        this.splitFeatureSuggestion = splitFeatureSuggestion;
        this.featureRequirement = featureRequirement;
    }
}

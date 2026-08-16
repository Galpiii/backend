package com.github.galpiii.galpi.domain.featurespec.entity;

import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
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
@Table(name = "duplicate_candidates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DuplicateCandidate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_id", nullable = false)
    private Feature feature;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_feature_id", nullable = false)
    private Feature targetFeature;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(nullable = false, length = 255)
    private String suggestedMergedName;

    @Column(nullable = false, length = 255)
    private String suggestedSection;

    @Builder
    private DuplicateCandidate(Feature feature, Feature targetFeature, String reason,
                               String suggestedMergedName, String suggestedSection) {
        this.feature = feature;
        this.targetFeature = targetFeature;
        this.reason = reason;
        this.suggestedMergedName = suggestedMergedName;
        this.suggestedSection = suggestedSection;
    }
}

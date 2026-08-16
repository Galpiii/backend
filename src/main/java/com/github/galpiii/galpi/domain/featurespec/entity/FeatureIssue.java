package com.github.galpiii.galpi.domain.featurespec.entity;

import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "feature_issues")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeatureIssue extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_id", nullable = false)
    private Feature feature;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FeatureIssueType issueType;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Builder
    private FeatureIssue(Feature feature, FeatureIssueType issueType, String description) {
        this.feature = feature;
        this.issueType = issueType;
        this.description = description;
    }
}

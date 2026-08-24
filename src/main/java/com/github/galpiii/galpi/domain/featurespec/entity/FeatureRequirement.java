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
@Table(name = "feature_requirements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeatureRequirement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_id", nullable = false)
    private Feature feature;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(columnDefinition = "text")
    private String sourceText;

    @Column(nullable = false)
    private int displayOrder;

    @Builder
    private FeatureRequirement(Feature feature, String content, String sourceText, int displayOrder) {
        this.feature = feature;
        this.content = content;
        this.sourceText = sourceText;
        this.displayOrder = displayOrder;
    }

    public void updateContent(String content) {
        this.content = content;
    }

    public void updateDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}

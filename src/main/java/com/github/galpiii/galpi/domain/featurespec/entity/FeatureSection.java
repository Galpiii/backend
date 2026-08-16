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
@Table(name = "feature_sections")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeatureSection extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spec_document_id", nullable = false)
    private SpecDocument specDocument;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 255)
    private String sourceTitle;

    @Column(nullable = false)
    private int displayOrder;

    private Integer pageStart;
    private Integer pageEnd;

    @Builder
    private FeatureSection(SpecDocument specDocument, String title, String sourceTitle,
                           int displayOrder, Integer pageStart, Integer pageEnd) {
        this.specDocument = specDocument;
        this.title = title;
        this.sourceTitle = sourceTitle;
        this.displayOrder = displayOrder;
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
    }
}

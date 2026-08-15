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
@Table(name = "features")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Feature extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spec_document_id", nullable = false)
    private SpecDocument specDocument;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id")
    private FeatureSection section;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false)
    private int displayOrder;

    private Integer sourcePageStart;
    private Integer sourcePageEnd;

    @Builder
    private Feature(SpecDocument specDocument, FeatureSection section, String name,
                    int displayOrder, Integer sourcePageStart, Integer sourcePageEnd) {
        this.specDocument = specDocument;
        this.section = section;
        this.name = name;
        this.displayOrder = displayOrder;
        this.sourcePageStart = sourcePageStart;
        this.sourcePageEnd = sourcePageEnd;
    }
}

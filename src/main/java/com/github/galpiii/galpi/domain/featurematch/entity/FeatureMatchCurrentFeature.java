package com.github.galpiii.galpi.domain.featurematch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "feature_match_current_features")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeatureMatchCurrentFeature {
    @Id
    private Long featureId;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 64)
    private String snapshotHash;

    public FeatureMatchCurrentFeature(long projectId, long featureId, String hash) {
        this.projectId = projectId;
        this.featureId = featureId;
        this.snapshotHash = hash;
    }
}

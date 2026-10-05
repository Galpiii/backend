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
@Table(name = "feature_match_current_pull_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeatureMatchCurrentPullRequest {
    @Id
    private Long pullRequestId;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 64)
    private String analysisSnapshotHash;

    @Column(length = 64)
    private String sourceSnapshotHash;

    public FeatureMatchCurrentPullRequest(long projectId, long pullRequestId, String analysisHash, String sourceHash) {
        this.projectId = projectId;
        this.pullRequestId = pullRequestId;
        this.analysisSnapshotHash = analysisHash;
        this.sourceSnapshotHash = sourceHash;
    }

    public void update(String analysisHash, String sourceHash) {
        this.analysisSnapshotHash = analysisHash;
        this.sourceSnapshotHash = sourceHash;
    }

    public void backfillSourceHash(String sourceHash) {
        if (sourceSnapshotHash == null) {
            this.sourceSnapshotHash = sourceHash;
        }
    }
}

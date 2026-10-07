package com.github.galpiii.galpi.domain.featurematch.entity;

import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.project.entity.Project;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "feature_match_current_states")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeatureMatchCurrentState {
    @Id
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spec_document_id", nullable = false)
    private SpecDocument specDocument;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "base_run_id")
    private FeatureMatchRun baseRun;

    private String featureSnapshotHash;

    public FeatureMatchCurrentState(Project project, SpecDocument document, FeatureMatchRun run, String hash) {
        this.projectId = project.getId();
        this.specDocument = document;
        this.baseRun = run;
        this.featureSnapshotHash = hash;
    }

    public void update(SpecDocument document, FeatureMatchRun run, String hash) {
        this.specDocument = document;
        this.baseRun = run;
        this.featureSnapshotHash = hash;
    }

}

package com.github.galpiii.galpi.domain.featurematch.entity;

import com.github.galpiii.galpi.domain.featurespec.entity.SpecDocument;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 한 번의 기능대조 실행과 생성 시점의 입력 범위.
 */
@Entity
@Getter
@Table(name = "feature_match_runs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeatureMatchRun extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spec_document_id", nullable = false)
    private SpecDocument specDocument;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FeatureMatchRunStatus status;

    @Column(nullable = false, length = 64)
    private String featureSnapshotHash;

    @Column(nullable = false)
    private int featureCount;

    @Column(nullable = false)
    private int eligiblePrCount;

    @Column(nullable = false)
    private int excludedFailedPrCount;

    @Column(nullable = false)
    private int excludedCancelledPrCount;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private FeatureMatchFailureCode failureCode;

    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;

    private FeatureMatchRun(Project project, SpecDocument document, User user, String hash,
                            int features, int eligible, int failed, int cancelled) {
        this.project = project;
        this.specDocument = document;
        this.user = user;
        this.featureSnapshotHash = hash;
        this.featureCount = features;
        this.eligiblePrCount = eligible;
        this.excludedFailedPrCount = failed;
        this.excludedCancelledPrCount = cancelled;
        this.status = FeatureMatchRunStatus.QUEUED;
    }

    public static FeatureMatchRun queue(Project project, SpecDocument document, User user, String hash,
                                        int features, int eligible, int failed, int cancelled) {
        return new FeatureMatchRun(project, document, user, hash, features, eligible, failed, cancelled);
    }

    public boolean isInFlight() {
        return status == FeatureMatchRunStatus.QUEUED || status == FeatureMatchRunStatus.RUNNING;
    }

    public void start() {
        if (status != FeatureMatchRunStatus.QUEUED) {
            return;
        }
        status = FeatureMatchRunStatus.RUNNING;
        startedAt = OffsetDateTime.now();
    }

    public void finish(FeatureMatchRunStatus terminalStatus, FeatureMatchFailureCode failureCode) {
        if (!isInFlight()) {
            return;
        }
        if (terminalStatus == FeatureMatchRunStatus.QUEUED || terminalStatus == FeatureMatchRunStatus.RUNNING) {
            throw new IllegalArgumentException("종료 상태가 필요합니다.");
        }
        this.status = terminalStatus;
        this.failureCode = failureCode;
        this.finishedAt = OffsetDateTime.now();
    }
}

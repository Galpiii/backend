package com.github.galpiii.galpi.domain.featurematch.entity;

import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
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
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * PR 하나의 대조 작업. 선점 토큰으로 만료된 워커의 저장을 거부한다.
 */
@Entity
@Getter
@Table(name = "feature_match_targets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeatureMatchTarget extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_match_run_id", nullable = false)
    private FeatureMatchRun featureMatchRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_analysis_id", nullable = false)
    private PullRequestAnalysis pullRequestAnalysis;

    @Column(nullable = false, length = 40)
    private String analysisHeadSha;

    @Column(nullable = false, length = 64)
    private String analysisSnapshotHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FeatureMatchTargetStatus status;

    @Column(length = 100)
    private String claimedBy;

    private OffsetDateTime claimedAt;
    private OffsetDateTime nextAttemptAt;
    @Column(nullable = false)
    private int attempts;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private FeatureMatchFailureCode failureCode;

    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;

    private FeatureMatchTarget(FeatureMatchRun run, PullRequestAnalysis analysis, String headSha, String hash) {
        this.featureMatchRun = run;
        this.pullRequestAnalysis = analysis;
        this.analysisHeadSha = headSha;
        this.analysisSnapshotHash = hash;
        this.status = FeatureMatchTargetStatus.PENDING;
    }

    public static FeatureMatchTarget pending(FeatureMatchRun run, PullRequestAnalysis analysis,
                                             String headSha, String hash) {
        return new FeatureMatchTarget(run, analysis, headSha, hash);
    }

    public void finish(String token, FeatureMatchTargetStatus status,
                       FeatureMatchFailureCode failureCode, OffsetDateTime nextAttemptAt) {
        if (this.status != FeatureMatchTargetStatus.RUNNING || !Objects.equals(claimedBy, token)) {
            return;
        }
        this.status = status;
        this.failureCode = failureCode;
        this.nextAttemptAt = nextAttemptAt;
        this.finishedAt = status == FeatureMatchTargetStatus.PENDING ? null : OffsetDateTime.now();
        release();
    }

    public void cancel(FeatureMatchFailureCode reason) {
        if (status != FeatureMatchTargetStatus.PENDING && status != FeatureMatchTargetStatus.RUNNING) {
            return;
        }
        status = FeatureMatchTargetStatus.CANCELLED;
        failureCode = reason;
        nextAttemptAt = null;
        finishedAt = OffsetDateTime.now();
        release();
    }

    private void release() {
        claimedBy = null;
        claimedAt = null;
    }
}

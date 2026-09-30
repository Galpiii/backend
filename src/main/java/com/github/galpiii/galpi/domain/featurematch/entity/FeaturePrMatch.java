package com.github.galpiii.galpi.domain.featurematch.entity;

import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.featurespec.entity.Feature;
import com.github.galpiii.galpi.domain.user.entity.User;
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

/**
 * AI 연결은 실행에 속하고, 사용자 연결은 재실행과 무관하게 유지한다.
 */
@Entity
@Getter
@Table(name = "feature_pr_matches")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeaturePrMatch extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feature_match_run_id")
    private FeatureMatchRun featureMatchRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feature_match_target_id")
    private FeatureMatchTarget featureMatchTarget;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_id", nullable = false)
    private Feature feature;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_id", nullable = false)
    private PullRequest pullRequest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FeatureMatchSource source;

    @Column(columnDefinition = "text")
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private FeaturePrMatch(FeatureMatchRun run, FeatureMatchTarget target, Feature feature,
                           PullRequest pullRequest, FeatureMatchSource source, String reason, User user) {
        this.featureMatchRun = run;
        this.featureMatchTarget = target;
        this.feature = feature;
        this.pullRequest = pullRequest;
        this.source = source;
        this.reason = reason;
        this.user = user;
    }

    public static FeaturePrMatch byAi(FeatureMatchRun run, FeatureMatchTarget target, Feature feature,
                                      PullRequest pullRequest, String reason) {
        return new FeaturePrMatch(run, target, feature, pullRequest, FeatureMatchSource.AI, reason, null);
    }

    public static FeaturePrMatch byUser(Feature feature, PullRequest pullRequest, User user) {
        return new FeaturePrMatch(null, null, feature, pullRequest, FeatureMatchSource.USER, null, user);
    }
}

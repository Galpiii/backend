package com.github.galpiii.galpi.domain.collection.entity;

import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자가 분석에서 뺀 PR.
 *
 * <p>수집은 그대로 유지하고 분석 대상에서만 뺀다. 물리 삭제하지 않는 이유는 되돌릴 수 있어야
 * 하기 때문이다 — 뺐다가 다시 넣으려면 GitHub을 다시 훑어야 한다면 사용자는 빼는 것을 주저한다.
 */
@Entity
@Getter
@Table(
        name = "pr_exclusions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pr_exclusions_pull_request",
                columnNames = "pull_request_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrExclusion extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_id", nullable = false)
    private PullRequest pullRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "excluded_by", nullable = false)
    private User excludedBy;

    @Column(length = 500)
    private String reason;

    private PrExclusion(PullRequest pullRequest, User excludedBy, String reason) {
        this.pullRequest = pullRequest;
        this.excludedBy = excludedBy;
        this.reason = reason;
    }

    public static PrExclusion of(PullRequest pullRequest, User excludedBy, String reason) {
        return new PrExclusion(pullRequest, excludedBy, reason);
    }
}

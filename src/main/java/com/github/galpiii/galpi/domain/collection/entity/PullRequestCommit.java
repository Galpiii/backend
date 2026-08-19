package com.github.galpiii.galpi.domain.collection.entity;

import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * PR에 포함된 커밋.
 *
 * <p>{@code message}에도 마스킹을 거친 값이 들어온다. 커밋 메시지는 사람이 자유롭게 쓰는
 * 필드라 토큰을 붙여 넣는 일이 실제로 있다.
 *
 * <p>작성자는 GitHub 계정과 연결되지 않을 수 있다. 커밋 author의 이메일이 GitHub 계정에
 * 매핑되지 않으면 {@code author}가 null로 오고, 그때는 로그인과 id가 모두 비어 있다.
 */
@Entity
@Getter
@Table(
        name = "pull_request_commits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pull_request_commits_pull_request_sha",
                columnNames = {"pull_request_id", "sha"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PullRequestCommit extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_id", nullable = false)
    private PullRequest pullRequest;

    @Column(nullable = false, length = 40)
    private String sha;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    // GitHub 계정이 매칭되지 않으면 login 대신 git author name이 들어올 수 있다.
    @Column(length = 255)
    private String authorLogin;

    private Long authorGithubId;

    @Column(nullable = false)
    private OffsetDateTime authoredAt;

    private PullRequestCommit(PullRequest pullRequest, String sha, String message,
                              String authorLogin, Long authorGithubId, OffsetDateTime authoredAt) {
        this.pullRequest = pullRequest;
        this.sha = sha;
        this.message = message;
        this.authorLogin = authorLogin;
        this.authorGithubId = authorGithubId;
        this.authoredAt = authoredAt;
    }

    public static PullRequestCommit of(PullRequest pullRequest, String sha, String message,
                                       String authorLogin, Long authorGithubId,
                                       OffsetDateTime authoredAt) {
        return new PullRequestCommit(pullRequest, sha, message, authorLogin, authorGithubId,
                authoredAt);
    }
}

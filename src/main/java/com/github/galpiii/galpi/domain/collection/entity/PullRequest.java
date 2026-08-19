package com.github.galpiii.galpi.domain.collection.entity;

import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.global.entity.BaseEntity;
import com.github.galpiii.galpi.global.persistence.IncompleteReasonsConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 병합된 PR 하나. draft·closed-unmerged·open은 수집하지 않으므로 {@code mergedAt}이 항상 있다.
 *
 * <p>{@code body}는 코드 원문이 아니라 저장하지만, 사용자가 자격증명을 붙여넣을 수 있어
 * 저장 전에 마스킹을 거친 값이 들어온다. 마스킹은 수집 쪽 책임이고 이 엔티티는 받은 값을
 * 그대로 담는다 — 엔티티가 스스로 마스킹하면 "마스킹을 거쳤는지" 여부를 호출부가 알 수 없다.
 */
@Entity
@Getter
@Table(
        name = "pull_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pull_requests_repository_number",
                columnNames = {"repository_id", "number"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PullRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private GithubRepository repository;

    /** 삭제된 GitHub 계정의 PR은 작성자가 비어 온다. 그 PR을 버리면 구현 근거가 사라진다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contributor_id")
    private Contributor contributor;

    @Column(nullable = false)
    private Long githubPullRequestId;

    @Column(nullable = false)
    private Integer number;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    @Column(nullable = false, length = 255)
    private String baseRef;

    @Column(nullable = false, length = 255)
    private String headRef;

    @Column(nullable = false, length = 40)
    private String baseSha;

    @Column(nullable = false, length = 40)
    private String headSha;

    @Column(length = 40)
    private String mergeCommitSha;

    @Column(nullable = false)
    private OffsetDateTime mergedAt;

    @Column(nullable = false)
    private OffsetDateTime createdAtGithub;

    @Column(nullable = false)
    private int changedFilesCount;

    @Column(nullable = false)
    private int additions;

    @Column(nullable = false)
    private int deletions;

    @Column(nullable = false, length = 500)
    private String htmlUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DataCompleteness dataCompleteness;

    @JdbcTypeCode(SqlTypes.JSON)
    @Convert(converter = IncompleteReasonsConverter.class)
    @Column(columnDefinition = "jsonb")
    private List<IncompleteReason> incompleteReasons;

    @Column(nullable = false)
    private OffsetDateTime collectedAt;

    @Builder
    private PullRequest(GithubRepository repository, Contributor contributor,
                        Long githubPullRequestId, Integer number, String title, String body,
                        String baseRef, String headRef, String baseSha, String headSha,
                        String mergeCommitSha, OffsetDateTime mergedAt,
                        OffsetDateTime createdAtGithub, int changedFilesCount, int additions,
                        int deletions, String htmlUrl, List<IncompleteReason> incompleteReasons) {
        this.repository = repository;
        this.contributor = contributor;
        this.githubPullRequestId = githubPullRequestId;
        this.number = number;
        this.title = title;
        this.body = body;
        this.baseRef = baseRef;
        this.headRef = headRef;
        this.baseSha = baseSha;
        this.headSha = headSha;
        this.mergeCommitSha = mergeCommitSha;
        this.mergedAt = mergedAt;
        this.createdAtGithub = createdAtGithub;
        this.changedFilesCount = changedFilesCount;
        this.additions = additions;
        this.deletions = deletions;
        this.htmlUrl = htmlUrl;
        this.collectedAt = OffsetDateTime.now();
        applyIncompleteReasons(incompleteReasons);
    }

    /**
     * 이미 수집한 PR을 다시 수집했을 때 값을 갱신한다.
     *
     * <p>PR은 병합된 뒤에도 제목·본문이 바뀔 수 있고, 지난번에 상한 때문에 못 가져온 부분이
     * 이번에는 올 수도 있다. 불완전 사유는 누적하지 않고 이번 수집 결과로 갈아 끼운다.
     */
    public void refresh(PullRequest collected) {
        this.contributor = collected.contributor;
        this.title = collected.title;
        this.body = collected.body;
        this.baseRef = collected.baseRef;
        this.headRef = collected.headRef;
        this.baseSha = collected.baseSha;
        this.headSha = collected.headSha;
        this.mergeCommitSha = collected.mergeCommitSha;
        this.mergedAt = collected.mergedAt;
        this.createdAtGithub = collected.createdAtGithub;
        this.changedFilesCount = collected.changedFilesCount;
        this.additions = collected.additions;
        this.deletions = collected.deletions;
        this.htmlUrl = collected.htmlUrl;
        this.collectedAt = OffsetDateTime.now();
        applyIncompleteReasons(collected.incompleteReasons);
    }

    private void applyIncompleteReasons(List<IncompleteReason> reasons) {
        List<IncompleteReason> distinct = reasons == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(reasons));
        this.incompleteReasons = distinct.isEmpty() ? null : distinct;
        this.dataCompleteness = distinct.isEmpty()
                ? DataCompleteness.COMPLETE
                : DataCompleteness.PARTIAL;
    }
}

package com.github.galpiii.galpi.domain.pullrequest.entity;

import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * PR 하나에 대한 AI 요약. 결과 테이블이자 큐다.
 *
 * <p>{@code installationId}와 {@code requestedBy}를 여기 들고 있는 것이 이 엔티티의 핵심이다.
 * 요약을 만들려면 patch를 GitHub에서 다시 받아야 하는데 워커에는 사용자 세션이 없다. 인계
 * 수집으로 검증된 두 값을 저장해 두면, 워커는 저장소에서 프로젝트 소유자를 거슬러 올라가거나
 * 캐시된 {@code repositories.installation_id}에 기대지 않아도 된다. 같은 head를 재수집해도
 * App 재설치가 반영되도록 이 실행 컨텍스트는 최신 값으로 갱신한다.
 *
 * <p>상태와 채워진 값의 짝은 DB CHECK 제약이 함께 지킨다. 그래서 상태를 되돌릴 때 결과·오류
 * 컬럼을 반드시 함께 비워야 하고, 그 일을 호출부에 맡기지 않으려고 전이 메서드로만 상태를
 * 바꾸게 해 뒀다.
 */
@Entity
@Getter
@Table(
        name = "pull_request_analyses",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pull_request_analyses_pull_request",
                columnNames = "pull_request_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PullRequestAnalysis extends BaseEntity {

    /** 요약 본문 상한. 넘으면 자르지 않고 실패로 본다 -- 자르면 문장이 반쯤 남는다. */
    public static final int MAX_SUMMARY_LENGTH = 300;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pull_request_id", nullable = false)
    private PullRequest pullRequest;

    @Column(nullable = false)
    private Long installationId;

    /** 외부 전송 동의를 다시 물을 대상. 워커가 세션 없이 도는 탓에 필요하다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PullRequestAnalysisStatus status;

    /** 어느 커밋 기준의 요약인지. 재수집으로 이 값이 바뀌면 요약은 낡은 것이 된다. */
    @Column(nullable = false, length = 40)
    private String headSha;

    @Column(columnDefinition = "text")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ChangeType changeType;

    /** 어떤 모델이 만든 요약인지. 모델을 바꾼 뒤 결과가 달라졌을 때 그 경계를 알 수 있어야 한다. */
    @Column(length = 100)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private SummaryFailureCode errorCode;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Column(length = 100)
    private String claimedBy;

    private OffsetDateTime claimedAt;

    /** rate limit 해제 전의 즉시 재선점을 막는다. {@code null}이면 바로 선점할 수 있다. */
    private OffsetDateTime nextAttemptAt;

    @Column(nullable = false)
    private int attempts;

    private OffsetDateTime analyzedAt;

    private PullRequestAnalysis(PullRequest pullRequest, Long installationId, User requestedBy,
                                String headSha) {
        this.pullRequest = pullRequest;
        this.installationId = installationId;
        this.requestedBy = requestedBy;
        this.headSha = headSha;
        this.status = PullRequestAnalysisStatus.PENDING;
        this.attempts = 0;
    }

    public static PullRequestAnalysis pending(PullRequest pullRequest, Long installationId,
                                              User requestedBy, String headSha) {
        return new PullRequestAnalysis(pullRequest, installationId, requestedBy, headSha);
    }

    /**
     * 재수집으로 기준 커밋이 바뀌었다. 지난 요약은 다른 커밋의 것이므로 버리고 다시 큐에 넣는다.
     *
     * <p>{@code installationId}와 {@code requestedBy}도 이번 인계 값으로 갈아 끼운다. 지난번
     * 인계 이후 저장소가 다른 설치로 옮겨 갔을 수 있다.
     */
    public void requeueForNewHead(Long installationId, User requestedBy, String headSha) {
        this.installationId = installationId;
        this.requestedBy = requestedBy;
        this.headSha = headSha;
        this.status = PullRequestAnalysisStatus.PENDING;
        this.attempts = 0;
        clearOutcome();
        this.nextAttemptAt = null;
        release();
    }

    /**
     * 실패한 요약을 다시 큐에 넣는다. 화면의 "실패한 PR만 다시 분석"이다.
     *
     * <p>{@code attempts}를 0으로 되돌린다. 사용자가 직접 누른 재시도라, 지난번 시도 횟수를
     * 이어받으면 한 번 만에 다시 상한에 걸린다.
     */
    public void retry() {
        this.status = PullRequestAnalysisStatus.PENDING;
        this.attempts = 0;
        clearOutcome();
        this.nextAttemptAt = null;
        release();
    }

    /** 실행 근거가 복구된 뒤 다시 수집됐다. 같은 head여도 취소된 작업은 새로 실행한다. */
    public void requeueAfterCancellation(Long installationId, User requestedBy, String headSha) {
        requeueForNewHead(installationId, requestedBy, headSha);
    }

    /** head가 같아 결과는 유지하되, 다음 실행에 쓸 검증된 설치와 요청자는 최신 값으로 맞춘다. */
    public void refreshExecutionContext(Long installationId, User requestedBy) {
        this.installationId = installationId;
        this.requestedBy = requestedBy;
    }

    /** 요약이 끝났다. 검증을 통과한 값만 들어온다. */
    public void complete(String summary, ChangeType changeType, String model) {
        this.status = PullRequestAnalysisStatus.COMPLETED;
        this.summary = summary;
        this.changeType = changeType;
        this.model = model;
        this.errorCode = null;
        this.errorMessage = null;
        this.analyzedAt = OffsetDateTime.now();
        this.nextAttemptAt = null;
        release();
    }

    public void fail(SummaryFailureCode errorCode, String errorMessage) {
        this.status = PullRequestAnalysisStatus.FAILED;
        this.summary = null;
        this.changeType = null;
        this.model = null;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.analyzedAt = OffsetDateTime.now();
        this.nextAttemptAt = null;
        release();
    }

    private void clearOutcome() {
        this.summary = null;
        this.changeType = null;
        this.model = null;
        this.errorCode = null;
        this.errorMessage = null;
        this.analyzedAt = null;
    }

    private void release() {
        this.claimedBy = null;
        this.claimedAt = null;
    }
}

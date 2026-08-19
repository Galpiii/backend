package com.github.galpiii.galpi.domain.analysis.entity;

import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 분석 작업 안의 저장소 하나. 테이블은 {@code analysis_run_repositories}다.
 *
 * <p>클래스 이름을 테이블명 그대로 {@code AnalysisRunRepository}로 두지 않은 것은 Spring Data의
 * 리포지토리 인터페이스와 이름이 겹치기 때문이다. {@code repositories}를
 * {@link GithubRepository}로 부르는 것과 같은 이유다.
 *
 * <p>저장소 단위 부분 실패가 여기 기록된다. 하나가 {@code FAILED}여도 나머지는 계속 진행하고
 * 작업 전체는 {@code PARTIALLY_COMPLETED}가 된다.
 *
 * <p>제외된 파일의 <b>목록</b>은 저장하지 않는다. 개수와 사유 요약만 남는다 —
 * {@code node_modules} 하나로 수만 행이 되는데, 그 목록이 분석 결과에 더해 주는 것은 없다.
 */
@Entity
@Getter
@Table(
        name = "analysis_run_repositories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_analysis_run_repositories_run_repository",
                columnNames = {"analysis_run_id", "repository_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisRunTarget extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analysis_run_id", nullable = false)
    private AnalysisRun analysisRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private GithubRepository repository;

    /**
     * 작업 생성 시점에 확정한 installation.
     *
     * <p>{@code repositories.installation_id}는 캐시라 그 사이에 바뀔 수 있다. 한 작업 안에서는
     * 같은 값으로 끝까지 가야 토큰 발급 묶음이 일관된다.
     */
    @Column(nullable = false)
    private Long installationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisRunTargetStatus status;

    /** stale 재대조의 기준점. 이 커밋으로 분석했다는 사실이 남아야 다음에 무엇이 바뀌었는지 안다. */
    @Column(length = 40)
    private String analyzedCommitSha;

    @Column(nullable = false)
    private int collectedFileCount;

    @Column(nullable = false)
    private long collectedBytes;

    @Column(nullable = false)
    private int excludedFileCount;

    @Column(nullable = false)
    private int prCollectedCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DataCompleteness dataCompleteness;

    @JdbcTypeCode(SqlTypes.JSON)
    @Convert(converter = IncompleteReasonsConverter.class)
    @Column(columnDefinition = "jsonb")
    private List<IncompleteReason> incompleteReasons;

    @Column(length = 50)
    private String errorCode;

    @Column(columnDefinition = "text")
    private String errorMessage;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    private AnalysisRunTarget(AnalysisRun analysisRun, GithubRepository repository,
                              Long installationId) {
        this.analysisRun = analysisRun;
        this.repository = repository;
        this.installationId = installationId;
        this.status = AnalysisRunTargetStatus.PENDING;
        this.dataCompleteness = DataCompleteness.COMPLETE;
    }

    public static AnalysisRunTarget pending(AnalysisRun analysisRun, GithubRepository repository,
                                            Long installationId) {
        return new AnalysisRunTarget(analysisRun, repository, installationId);
    }

    public void startCollecting() {
        this.status = AnalysisRunTargetStatus.COLLECTING;
        this.startedAt = OffsetDateTime.now();
    }

    public void complete(String analyzedCommitSha, int collectedFileCount, long collectedBytes,
                         int excludedFileCount, int prCollectedCount,
                         List<IncompleteReason> incompleteReasons) {
        this.analyzedCommitSha = analyzedCommitSha;
        this.collectedFileCount = collectedFileCount;
        this.collectedBytes = collectedBytes;
        this.excludedFileCount = excludedFileCount;
        this.prCollectedCount = prCollectedCount;
        applyIncompleteReasons(incompleteReasons);
        this.status = AnalysisRunTargetStatus.COMPLETED;
        this.finishedAt = OffsetDateTime.now();
    }

    /**
     * 이 저장소만 실패로 끝낸다. 프로젝트의 나머지 저장소는 계속 진행한다.
     *
     * <p>{@code errorMessage}는 마스킹을 거친 값이어야 한다. GitHub 응답 본문이 그대로 들어오면
     * 토큰이 DB에 남을 수 있다.
     */
    public void fail(String errorCode, String errorMessage) {
        this.status = AnalysisRunTargetStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.finishedAt = OffsetDateTime.now();
    }

    /** 작업이 중단돼 손대지 못한 저장소. 실패와 구분해야 사용자가 재시도의 의미를 안다. */
    public void skip(List<IncompleteReason> incompleteReasons) {
        applyIncompleteReasons(incompleteReasons);
        this.status = AnalysisRunTargetStatus.SKIPPED;
        this.finishedAt = OffsetDateTime.now();
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

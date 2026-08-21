package com.github.galpiii.galpi.domain.analysis.entity;

import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.entity.BaseEntity;
import com.github.galpiii.galpi.global.persistence.InstallationSnapshotConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 분석 작업 하나.
 *
 * <p>작업을 만드는 시점에는 사용자 세션이 있어 "이 사용자가 지금 접근할 수 있는 저장소"를
 * GitHub에 물어 확인할 수 있다. 그때 확정한 installation을 작업에 고정해 두기 때문에, 워커는
 * user access token 없이 installation token만으로 동작한다. 이 구조 덕분에 "user token은
 * 세션 동안만 쓴다"는 원칙과 몇 분씩 걸리는 분석이 양립한다.
 *
 * <p>고정한 값은 두 군데에 남는다. 워커가 실제로 읽는 것은 저장소별 행
 * ({@link AnalysisRunTarget#getInstallationId()})이고, {@code installationSnapshot}은
 * 그 매핑을 작업 단위로 함께 남긴 생성 시점 기록이다. 실행 경로에서는 읽지 않는다.
 *
 * <p>재개 스케줄러·checkpoint·재진입은 Phase 1의 범위가 아니다. rate limit에 걸리면 멈추고
 * 사용자가 다시 누른다.
 */
@Entity
@Getter
@Table(name = "analysis_runs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisRun extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AnalysisRunStatus status;

    /**
     * 생성 시점에 확정한 {@code githubRepositoryId -> installationId} 매핑.
     *
     * <p>실행 경로는 이 값을 읽지 않는다. 워커는 저장소별로 펼쳐 둔
     * {@code analysis_run_repositories.installation_id}를 보고 토큰을 묶는다. 여기 남기는
     * 것은 "그때 무엇을 근거로 이 작업을 만들었는지"를 작업 단위로 함께 보기 위해서다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Convert(converter = InstallationSnapshotConverter.class)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<Long, Long> installationSnapshot;

    @Column(length = 100)
    private String claimedBy;

    private OffsetDateTime claimedAt;

    @Column(nullable = false)
    private int attempts;

    private OffsetDateTime rateLimitResumeAt;

    @Column(length = 50)
    private String errorCode;

    @Column(columnDefinition = "text")
    private String errorMessage;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    private AnalysisRun(Project project, User requestedBy, Map<Long, Long> installationSnapshot) {
        this.project = project;
        this.requestedBy = requestedBy;
        this.installationSnapshot = installationSnapshot;
        this.status = AnalysisRunStatus.QUEUED;
        this.attempts = 0;
    }

    public static AnalysisRun queue(Project project, User requestedBy,
                                    Map<Long, Long> installationSnapshot) {
        return new AnalysisRun(project, requestedBy, installationSnapshot);
    }

    /**
     * 워커가 이 작업을 끝냈다고 표시한다.
     *
     * <p>저장소 하나가 실패해도 전체는 {@code PARTIALLY_COMPLETED}까지만 간다. 전부 실패한
     * 경우에만 {@code FAILED}다.
     */
    public void finish(AnalysisRunStatus terminalStatus) {
        if (isCancelled()) {
            return;
        }
        this.status = terminalStatus;
        this.finishedAt = OffsetDateTime.now();
    }

    public void fail(String errorCode, String errorMessage) {
        if (isCancelled()) {
            return;
        }
        this.status = AnalysisRunStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.finishedAt = OffsetDateTime.now();
    }

    /**
     * rate limit으로 중단한다. 남은 저장소는 손대지 않은 채로 둔다.
     *
     * <p>{@code resumeAt}은 표시용이다. 이 시각이 지나면 사용자가 다시 시도할 수 있다는 안내에
     * 쓰고, 서버가 이 시각에 스스로 깨어나지 않는다.
     */
    public void markRateLimited(OffsetDateTime resumeAt) {
        if (isCancelled()) {
            return;
        }
        this.status = AnalysisRunStatus.RATE_LIMITED;
        this.rateLimitResumeAt = resumeAt;
        this.finishedAt = OffsetDateTime.now();
    }

    /**
     * 취소된 작업은 어떤 결과로도 덮이지 않는다.
     *
     * <p>프로젝트를 지운 순간 CANCELLED가 되지만 워커는 저장소 하나를 마저 끝내고 돌아온다.
     * 그때 COMPLETED로 마무리하면 지운 프로젝트의 분석이 성공한 것으로 남는다.
     */
    private boolean isCancelled() {
        return status == AnalysisRunStatus.CANCELLED;
    }

    public boolean isFinished() {
        return status.isTerminal() || status == AnalysisRunStatus.RATE_LIMITED;
    }
}

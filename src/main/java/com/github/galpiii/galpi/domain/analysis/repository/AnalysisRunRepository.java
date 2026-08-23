package com.github.galpiii.galpi.domain.analysis.repository;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, Long> {

    Optional<AnalysisRun> findByIdAndProjectId(Long id, Long projectId);

    /**
     * 아직 끝나지 않은 작업이 있는지.
     *
     * <p>같은 프로젝트에 작업 두 개가 동시에 돌면 같은 저장소를 두 번 수집하며 서로의 결과를
     * 덮는다. 이 조회는 빠른 실패를 위한 것이고, 동시에 통과하는 경쟁 조건은 DB의
     * {@code uk_analysis_runs_project_in_flight} 부분 유니크 인덱스가 최종적으로 막는다.
     */
    boolean existsByProjectIdAndStatusIn(Long projectId, Collection<AnalysisRunStatus> statuses);

    /**
     * 선점할 작업 하나를 잠근 채로 고른다.
     *
     * <p>{@code SKIP LOCKED}가 이 쿼리의 전부다. 워커를 여러 개 띄워도 이미 다른 트랜잭션이
     * 잠근 행은 건너뛰므로 같은 작업이 두 번 실행되지 않는다. {@code SKIP LOCKED} 없이
     * {@code FOR UPDATE}만 쓰면 두 번째 워커가 첫 워커의 커밋을 기다렸다가 이미 RUNNING이 된
     * 행을 다시 읽는다 — 중복 실행은 막히지만 워커가 놀게 된다.
     *
     * <p>{@code leaseExpiredBefore}는 Java에서 계산해 넘긴다. SQL 안에서 interval 산술을 하면
     * 방언에 묶이고, 테스트에서 임계 시각을 흔들어 보기도 어렵다.
     *
     * <p>이 메서드는 반드시 선점 트랜잭션 안에서 불러야 한다. 잠금은 트랜잭션이 끝나면 풀린다.
     */
    @Query(value = """
            SELECT id
              FROM analysis_runs
             WHERE status = 'QUEUED'
                OR (status = 'RUNNING' AND claimed_at < :leaseExpiredBefore)
             ORDER BY CASE WHEN status = 'QUEUED' THEN 0 ELSE 1 END, created_at
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Long> findClaimableId(@Param("leaseExpiredBefore") OffsetDateTime leaseExpiredBefore);

    /**
     * 고른 작업을 RUNNING으로 넘긴다.
     *
     * <p>QUEUED 또는 lease가 지난 RUNNING 조건을 다시 거는 것은 방어다. 잠금이 이미 경합을
     * 막지만, 이 조건이 있으면 잠금 없이 불렸을 때도 잘못된 상태 전이를 막는다.
     *
     * <p>네이티브 갱신이라 {@code @LastModifiedDate}가 동작하지 않는다. {@code updated_at}을
     * 직접 채우지 않으면 이 행만 감사 컬럼이 멈춘다.
    */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE analysis_runs
               SET status = 'RUNNING',
                   claimed_by = :workerId,
                   claimed_at = now(),
                   attempts = attempts + 1,
                   started_at = COALESCE(started_at, now()),
                   updated_at = now()
             WHERE id = :id
               AND (status = 'QUEUED'
                    OR (status = 'RUNNING' AND claimed_at < :leaseExpiredBefore))
            """, nativeQuery = true)
    int claim(@Param("id") Long id,
              @Param("workerId") String workerId,
              @Param("leaseExpiredBefore") OffsetDateTime leaseExpiredBefore);

    /**
     * 프로젝트가 삭제될 때 아직 끝나지 않은 작업을 취소한다.
     *
     * <p>지워진 프로젝트의 저장소를 계속 수집하는 것을 막는다. 워커가 이미 선점한 작업은
     * 이 갱신만으로 멈추지 않으므로, 실행 쪽이 저장소를 하나 끝낼 때마다 취소 여부를
     * 다시 확인한다({@link #isAbandoned}).
     */
    default int cancelInFlight(Long projectId, OffsetDateTime now) {
        return cancel(projectId, AnalysisRunStatus.CANCELLED,
                List.of(AnalysisRunStatus.QUEUED, AnalysisRunStatus.RUNNING), now);
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AnalysisRun run
               set run.status = :cancelled,
                   run.finishedAt = :now,
                   run.updatedAt = :now
             where run.project.id = :projectId
               and run.status in :inFlight
            """)
    int cancel(@Param("projectId") Long projectId,
               @Param("cancelled") AnalysisRunStatus cancelled,
               @Param("inFlight") Collection<AnalysisRunStatus> inFlight,
               @Param("now") OffsetDateTime now);

    /**
     * GitHub 연결이 끊겼을 때 이 사용자의 프로젝트에서 아직 끝나지 않은 작업을 취소한다.
     *
     * <p>작업은 "이 사용자가 지금 이 저장소에 접근할 수 있다"는 확인을 근거로 만들어졌다.
     * 연결을 끊은 순간 그 근거가 사라지므로, 워커가 installation token만으로 계속 수집할 수
     * 있다는 사실이 오히려 문제다. 프로젝트 삭제와 마찬가지로 이미 선점된 작업은 실행 쪽이
     * 저장소마다 {@link #isAbandoned}를 다시 보고 멈춘다.
     */
    default int cancelInFlightByOwner(Long ownerId, OffsetDateTime now) {
        return cancelByOwner(ownerId, AnalysisRunStatus.CANCELLED,
                List.of(AnalysisRunStatus.QUEUED, AnalysisRunStatus.RUNNING), now);
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AnalysisRun run
               set run.status = :cancelled,
                   run.finishedAt = :now,
                   run.updatedAt = :now
             where run.project.id in (select project.id from Project project
                                       where project.owner.id = :ownerId)
               and run.status in :inFlight
            """)
    int cancelByOwner(@Param("ownerId") Long ownerId,
                      @Param("cancelled") AnalysisRunStatus cancelled,
                      @Param("inFlight") Collection<AnalysisRunStatus> inFlight,
                      @Param("now") OffsetDateTime now);

    /**
     * 이 작업을 계속 진행할 이유가 사라졌는지.
     *
     * <p>취소됐거나 프로젝트가 삭제된 경우다. 워커는 저장소 사이의 체크포인트마다 이것을 보고
     * 남은 저장소를 시작하지 않는다 — 저장소 하나가 몇 분씩 걸리므로, 확인하지 않으면 삭제
     * 직후에도 프로젝트 전체를 끝까지 수집한다.
     */
    default boolean isAbandoned(Long runId) {
        return isAbandoned(runId, AnalysisRunStatus.CANCELLED);
    }

    @Query("""
            select count(run.id) > 0
              from AnalysisRun run
             where run.id = :runId
               and (run.status = :cancelled or run.project.deletedAt is not null)
            """)
    boolean isAbandoned(@Param("runId") Long runId,
                        @Param("cancelled") AnalysisRunStatus cancelled);

    /** 살아 있는 워커가 자신의 lease만 연장한다. 소유자가 달라졌다면 갱신하지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE analysis_runs
               SET claimed_at = now(),
                   updated_at = now()
             WHERE id = :id
               AND status = 'RUNNING'
               AND claimed_by = :workerId
            """, nativeQuery = true)
    int heartbeat(@Param("id") Long id, @Param("workerId") String workerId);
}

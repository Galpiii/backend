package com.github.galpiii.galpi.domain.analysis.repository;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, Long> {

    Optional<AnalysisRun> findByIdAndProjectId(Long id, Long projectId);

    /**
     * 아직 끝나지 않은 작업이 있는지.
     *
     * <p>같은 프로젝트에 작업 두 개가 동시에 돌면 같은 저장소를 두 번 수집하며 서로의 결과를
     * 덮는다. 완벽한 방어는 아니다 — 두 요청이 정확히 같은 순간에 오면 둘 다 통과할 수 있다.
     * 그 경우에도 데이터가 깨지지는 않고(수집은 멱등하다) 호출량만 두 배가 된다.
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
               AND (claimed_at IS NULL OR claimed_at < :leaseExpiredBefore)
             ORDER BY created_at
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Long> findClaimableId(@Param("leaseExpiredBefore") OffsetDateTime leaseExpiredBefore);

    /**
     * 고른 작업을 RUNNING으로 넘긴다.
     *
     * <p>{@code status = 'QUEUED'} 조건을 다시 거는 것은 방어다. 잠금이 이미 경합을 막지만,
     * 이 조건이 있으면 잠금 없이 불렸을 때도 0을 돌려주고 조용히 넘어간다.
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
               AND status = 'QUEUED'
            """, nativeQuery = true)
    int claim(@Param("id") Long id, @Param("workerId") String workerId);
}

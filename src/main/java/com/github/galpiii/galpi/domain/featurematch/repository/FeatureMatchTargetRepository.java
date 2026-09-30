package com.github.galpiii.galpi.domain.featurematch.repository;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchTarget;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchFailureCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface FeatureMatchTargetRepository extends JpaRepository<FeatureMatchTarget, Long> {

    List<FeatureMatchTarget> findAllByFeatureMatchRunId(Long runId);

    /**
     * analysis와 동일하게 잠긴 작업은 건너뛴다. 반드시 선점 트랜잭션 안에서 호출한다.
     */
    @Query(value = """
            SELECT target.id
              FROM feature_match_targets target
              JOIN feature_match_runs run ON run.id = target.feature_match_run_id
             WHERE run.status IN ('QUEUED', 'RUNNING')
               AND ((target.status = 'PENDING'
                     AND (target.next_attempt_at IS NULL OR target.next_attempt_at <= now()))
                    OR (target.status = 'RUNNING' AND target.claimed_at < :expired))
             ORDER BY target.id
             LIMIT :limit
             FOR UPDATE OF target SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findClaimableIds(@Param("expired") OffsetDateTime expired, @Param("limit") int limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE feature_match_targets
               SET status = 'RUNNING', claimed_by = :token, claimed_at = now(),
                   attempts = attempts + 1, started_at = coalesce(started_at, now()), updated_at = now()
             WHERE id = :id
               AND ((status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now()))
                    OR (status = 'RUNNING' AND claimed_at < :expired))
            """, nativeQuery = true)
    int claim(@Param("id") Long id, @Param("token") String token, @Param("expired") OffsetDateTime expired);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE feature_match_targets
               SET claimed_at = now(), updated_at = now()
             WHERE id = :id AND claimed_by = :token AND status = 'RUNNING'
            """, nativeQuery = true)
    int heartbeat(@Param("id") long id, @Param("token") String token);

    /**
     * 실행기에 전달하지 못한 선점만 되돌린다. 실제 AI 시도가 아니므로 횟수도 복구한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE feature_match_targets
               SET status = 'PENDING', claimed_by = NULL, claimed_at = NULL,
                   attempts = greatest(attempts - 1, 0), updated_at = now()
             WHERE id = :id AND claimed_by = :token AND status = 'RUNNING'
            """, nativeQuery = true)
    int releaseClaim(@Param("id") long id, @Param("token") String token);

    /**
     * 대표 원인: 발생 횟수 내림차순, 동률이면 코드명 오름차순.
     */
    @Query("""
            select target.failureCode as failureCode, count(target) as total
              from FeatureMatchTarget target
             where target.featureMatchRun.id = :runId and target.status = 'FAILED'
               and target.failureCode is not null
             group by target.failureCode
             order by count(target) desc, target.failureCode asc
            """)
    List<FailureCodeCount> countFailures(@Param("runId") long runId);

    interface FailureCodeCount {
        FeatureMatchFailureCode getFailureCode();

        long getTotal();
    }
}

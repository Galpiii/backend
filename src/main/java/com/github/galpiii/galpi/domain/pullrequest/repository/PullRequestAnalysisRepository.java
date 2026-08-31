package com.github.galpiii.galpi.domain.pullrequest.repository;

import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.support.SummaryHandoffState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PullRequestAnalysisRepository extends JpaRepository<PullRequestAnalysis, Long> {

    Optional<PullRequestAnalysis> findByPullRequestId(Long pullRequestId);

    /**
     * 외부 전송 직전 claim·생존·연결·현재 동의를 한 쿼리로 읽는다.
     *
     * <p>상태를 엔티티로 반환하지 않는다. 워커가 이미 들고 있는 준영속 스냅샷이 아니라,
     * 이 SELECT가 본 현재 값만 관문 판단에 쓴다.
     */
    @Query(value = """
            SELECT analysis.status AS "status",
                   analysis.claimed_by AS "claimedBy",
                   (project.deleted_at IS NOT NULL) AS "projectDeleted",
                   (repository.unlinked_at IS NOT NULL) AS "repositoryUnlinked",
                   requester.github_connection_status AS "githubConnectionStatus",
                   EXISTS (
                       SELECT 1
                         FROM ai_data_consents consent
                        WHERE consent.user_id = analysis.requested_by
                          AND consent.consent_version = :consentVersion
                          AND consent.notice_hash = :noticeHash
                   ) AS "consented"
              FROM pull_request_analyses analysis
              JOIN pull_requests pull_request ON pull_request.id = analysis.pull_request_id
              JOIN repositories repository ON repository.id = pull_request.repository_id
              JOIN projects project ON project.id = repository.project_id
              JOIN users requester ON requester.id = analysis.requested_by
             WHERE analysis.id = :analysisId
            """, nativeQuery = true)
    Optional<SummaryHandoffState> findForHandoff(
            @Param("analysisId") Long analysisId,
            @Param("consentVersion") String consentVersion,
            @Param("noticeHash") String noticeHash);

    /**
     * 인계가 이미 만들어 둔 요약 행을 PR id로 한 번에 읽는다.
     *
     * <p>PR 하나씩 조회하면 저장소당 300번이 된다. 인계는 스냅샷의 PR 전체를 한 묶음으로
     * 처리하므로 조회도 한 번이어야 한다.
     */
    @Query("""
            select analysis from PullRequestAnalysis analysis
             where analysis.pullRequest.id in :pullRequestIds
            """)
    List<PullRequestAnalysis> findAllByPullRequestIdIn(
            @Param("pullRequestIds") Collection<Long> pullRequestIds);

    /**
     * 선점할 요약을 배치로 잠근 채 고른다.
     *
     * <p>{@code SKIP LOCKED}가 이 쿼리의 전부다. 워커를 여러 개 띄워도 이미 다른 트랜잭션이
     * 잠근 행은 건너뛰므로 같은 PR을 두 번 요약하지 않는다. {@code AnalysisRunRepository}가
     * 작업 단위로 하는 일을 여기서는 PR 단위로 한다 -- 요약 하나는 몇 초라 하나씩 집으면
     * 폴링 주기가 처리량을 결정해 버린다.
     *
     * <p>{@code leaseExpiredBefore}는 Java에서 계산해 넘긴다. SQL 안에서 interval 산술을 하면
     * 방언에 묶이고, 테스트에서 임계 시각을 흔들어 보기도 어렵다.
     *
     * <p>반드시 선점 트랜잭션 안에서 불러야 한다. 잠금은 트랜잭션이 끝나면 풀린다.
     */
    @Query(value = """
            SELECT id
              FROM pull_request_analyses
             WHERE (status = 'PENDING'
                    AND (next_attempt_at IS NULL OR next_attempt_at <= now()))
                OR (status = 'RUNNING' AND claimed_at < :leaseExpiredBefore)
             ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, created_at
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findClaimableIds(@Param("leaseExpiredBefore") OffsetDateTime leaseExpiredBefore,
                                @Param("batchSize") int batchSize);

    /**
     * 고른 요약을 RUNNING으로 넘긴다.
     *
     * <p>상태 조건을 다시 거는 것은 방어다. 잠금이 이미 경합을 막지만, 이 조건이 있으면
     * 잠금 없이 불렸을 때도 잘못된 상태 전이를 막는다.
     *
     * <p>네이티브 갱신이라 {@code @LastModifiedDate}가 동작하지 않는다. {@code updated_at}을
     * 직접 채우지 않으면 이 행만 감사 컬럼이 멈춘다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE pull_request_analyses
               SET status = 'RUNNING',
                   claimed_by = :workerId,
                   claimed_at = now(),
                   next_attempt_at = NULL,
                   attempts = attempts + 1,
                   updated_at = now()
             WHERE id IN (:ids)
               AND ((status = 'PENDING'
                     AND (next_attempt_at IS NULL OR next_attempt_at <= now()))
                    OR (status = 'RUNNING' AND claimed_at < :leaseExpiredBefore))
            """, nativeQuery = true)
    int claim(@Param("ids") Collection<Long> ids,
              @Param("workerId") String workerId,
              @Param("leaseExpiredBefore") OffsetDateTime leaseExpiredBefore);

    /** 살아 있는 워커가 자신의 lease만 연장한다. 소유자가 달라졌다면 갱신하지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE pull_request_analyses
               SET claimed_at = now(),
                   updated_at = now()
             WHERE id IN (:ids)
               AND status = 'RUNNING'
               AND claimed_by = :workerId
            """, nativeQuery = true)
    int heartbeat(@Param("ids") Collection<Long> ids, @Param("workerId") String workerId);

    /**
     * 선점한 요약을 PR·저장소까지 한 번에 읽는다.
     *
     * <p>{@code PullRequest}의 연관이 전부 {@code LAZY}라 fetch join 없이 돌면 요약 하나마다
     * 저장소·기여자 조회가 따라붙는다. 워커는 저장소 이름과 PR 본문을 반드시 쓴다.
     *
     * <p>{@code requestedBy}까지 함께 읽는다. 워커는 트랜잭션 밖에서 돌아 준영속 엔티티를
     * 들고 있고, 그 상태에서 프록시를 건드리면 터진다. id 하나 쓰려고 그 위험을 지지 않는다.
     *
     * <p>소유자 조건을 다시 거는 것이 중요하다. 선점 갱신이 일부 행만 바꿨을 수 있고 -- 잠금이
     * 있어 거의 오지 않지만 -- 그때 남의 워커가 들고 있는 행까지 실행하면 같은 PR을 두 번
     * 요약하게 된다. 선점에 실제로 성공한 것만 여기서 걸러진다.
     */
    @Query("""
            select analysis from PullRequestAnalysis analysis
              join fetch analysis.pullRequest pullRequest
              join fetch pullRequest.repository
              join fetch analysis.requestedBy
             where analysis.id in :ids
               and analysis.status = :running
               and analysis.claimedBy = :workerId
            """)
    List<PullRequestAnalysis> findAllForExecution(@Param("ids") Collection<Long> ids,
                                                  @Param("workerId") String workerId,
                                                  @Param("running") PullRequestAnalysisStatus running);

    /** lease를 여전히 소유한 워커의 성공만 반영한다. 외부 호출 뒤의 stale write를 막는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PullRequestAnalysis analysis
               set analysis.status = :completed,
                   analysis.summary = :summary,
                   analysis.changeType = :changeType,
                   analysis.model = :model,
                   analysis.errorCode = null,
                   analysis.errorMessage = null,
                   analysis.analyzedAt = :now,
                   analysis.nextAttemptAt = null,
                   analysis.claimedBy = null,
                   analysis.claimedAt = null,
                   analysis.updatedAt = :now
             where analysis.id = :analysisId
               and analysis.status = :running
               and analysis.claimedBy = :workerId
            """)
    int completeClaimed(@Param("analysisId") Long analysisId,
                        @Param("workerId") String workerId,
                        @Param("running") PullRequestAnalysisStatus running,
                        @Param("completed") PullRequestAnalysisStatus completed,
                        @Param("summary") String summary,
                        @Param("changeType") ChangeType changeType,
                        @Param("model") String model,
                        @Param("now") OffsetDateTime now);

    /** lease를 여전히 소유한 워커의 실패만 반영한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PullRequestAnalysis analysis
               set analysis.status = :failed,
                   analysis.summary = null,
                   analysis.changeType = null,
                   analysis.model = null,
                   analysis.errorCode = :errorCode,
                   analysis.errorMessage = :errorMessage,
                   analysis.analyzedAt = :now,
                   analysis.nextAttemptAt = null,
                   analysis.claimedBy = null,
                   analysis.claimedAt = null,
                   analysis.updatedAt = :now
             where analysis.id = :analysisId
               and analysis.status = :running
               and analysis.claimedBy = :workerId
            """)
    int failClaimed(@Param("analysisId") Long analysisId,
                    @Param("workerId") String workerId,
                    @Param("running") PullRequestAnalysisStatus running,
                    @Param("failed") PullRequestAnalysisStatus failed,
                    @Param("errorCode") SummaryFailureCode errorCode,
                    @Param("errorMessage") String errorMessage,
                    @Param("now") OffsetDateTime now);

    /** 실행 근거가 사라진 RUNNING 행을 취소한다. stale worker의 결과도 함께 거부된다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PullRequestAnalysis analysis
               set analysis.status = :cancelled,
                   analysis.summary = null,
                   analysis.changeType = null,
                   analysis.model = null,
                   analysis.errorCode = :reason,
                   analysis.errorMessage = null,
                   analysis.analyzedAt = null,
                   analysis.nextAttemptAt = null,
                   analysis.claimedBy = null,
                   analysis.claimedAt = null,
                   analysis.updatedAt = :now
             where analysis.id = :analysisId
               and analysis.status = :running
               and analysis.claimedBy = :workerId
            """)
    int cancelClaimed(@Param("analysisId") Long analysisId,
                      @Param("workerId") String workerId,
                      @Param("running") PullRequestAnalysisStatus running,
                      @Param("cancelled") PullRequestAnalysisStatus cancelled,
                      @Param("reason") SummaryFailureCode reason,
                      @Param("now") OffsetDateTime now);

    /** 일시 실패를 지정 시각까지 미룬다. rate limit과 달리 시도 횟수는 되돌리지 않는다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PullRequestAnalysis analysis
               set analysis.status = :pending,
                   analysis.summary = null,
                   analysis.changeType = null,
                   analysis.model = null,
                   analysis.errorCode = null,
                   analysis.errorMessage = null,
                   analysis.analyzedAt = null,
                   analysis.nextAttemptAt = :retryAt,
                   analysis.claimedBy = null,
                   analysis.claimedAt = null,
                   analysis.updatedAt = :now
             where analysis.id = :analysisId
               and analysis.status = :running
               and analysis.claimedBy = :workerId
            """)
    int deferClaimedForRetry(@Param("analysisId") Long analysisId,
                             @Param("workerId") String workerId,
                             @Param("retryAt") OffsetDateTime retryAt,
                             @Param("now") OffsetDateTime now);

    /** rate limit은 실행 실패가 아니므로 선점 때 올린 횟수를 되돌리고 해제 시각까지 미룬다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE pull_request_analyses
               SET status = 'PENDING',
                   summary = NULL,
                   change_type = NULL,
                   model = NULL,
                   error_code = NULL,
                   error_message = NULL,
                   analyzed_at = NULL,
                   next_attempt_at = :retryAt,
                   claimed_by = NULL,
                   claimed_at = NULL,
                   attempts = GREATEST(attempts - 1, 0),
                   updated_at = :now
             WHERE id = :analysisId
               AND status = 'RUNNING'
               AND claimed_by = :workerId
            """, nativeQuery = true)
    int deferClaimedForRateLimit(@Param("analysisId") Long analysisId,
                                 @Param("workerId") String workerId,
                                 @Param("retryAt") OffsetDateTime retryAt,
                                 @Param("now") OffsetDateTime now);

    /**
     * 실패한 요약을 한 번에 큐로 되돌린다. 화면의 "실패한 PR만 다시 분석"이다.
     *
     * <p>진행 중({@code PENDING}/{@code RUNNING})인 행은 조건에서 빠진다 -- 돌고 있는 요약을
     * 되돌리면 워커가 끝낸 결과와 이 갱신이 서로를 덮는다.
     *
     * <p>벌크 갱신이라 결과·오류 컬럼을 함께 비운다. DB의 CHECK 제약이 "FAILED면 error_code가
     * 있어야 한다"를 강제하므로 상태만 바꾸면 제약에 걸린다.
     *
     * <p>저장소를 좁히는 갈래를 {@code :repositoryId is null}로 한 쿼리에 합치지 않았다.
     * 널 파라미터의 타입 추론이 방언마다 달라 조용히 어긋나기 쉽고, 두 문장이 읽기에도 더 낫다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PullRequestAnalysis analysis
               set analysis.status = :pending,
                   analysis.attempts = 0,
                   analysis.summary = null,
                   analysis.changeType = null,
                   analysis.model = null,
                   analysis.errorCode = null,
                   analysis.errorMessage = null,
                   analysis.analyzedAt = null,
                   analysis.nextAttemptAt = null,
                   analysis.claimedBy = null,
                   analysis.claimedAt = null,
                   analysis.updatedAt = :now
             where analysis.status = :failed
               and analysis.pullRequest.id in (
                     select pullRequest.id from PullRequest pullRequest
                      where pullRequest.repository.project.id = :projectId
                        and pullRequest.repository.unlinkedAt is null)
            """)
    int requeueFailedInProject(@Param("projectId") Long projectId,
                               @Param("pending") PullRequestAnalysisStatus pending,
                               @Param("failed") PullRequestAnalysisStatus failed,
                               @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PullRequestAnalysis analysis
               set analysis.status = :pending,
                   analysis.attempts = 0,
                   analysis.summary = null,
                   analysis.changeType = null,
                   analysis.model = null,
                   analysis.errorCode = null,
                   analysis.errorMessage = null,
                   analysis.analyzedAt = null,
                   analysis.nextAttemptAt = null,
                   analysis.claimedBy = null,
                   analysis.claimedAt = null,
                   analysis.updatedAt = :now
             where analysis.status = :failed
               and analysis.pullRequest.id in (
                     select pullRequest.id from PullRequest pullRequest
                      where pullRequest.repository.id = :repositoryId
                        and pullRequest.repository.unlinkedAt is null)
            """)
    int requeueFailedInRepository(@Param("repositoryId") Long repositoryId,
                                  @Param("pending") PullRequestAnalysisStatus pending,
                                  @Param("failed") PullRequestAnalysisStatus failed,
                                  @Param("now") OffsetDateTime now);

    default int cancelInFlightByProject(Long projectId, SummaryFailureCode reason,
                                        OffsetDateTime now) {
        return cancelByProject(projectId, PullRequestAnalysisStatus.CANCELLED,
                List.of(PullRequestAnalysisStatus.PENDING, PullRequestAnalysisStatus.RUNNING),
                reason, now);
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PullRequestAnalysis analysis
               set analysis.status = :cancelled,
                   analysis.summary = null,
                   analysis.changeType = null,
                   analysis.model = null,
                   analysis.errorCode = :reason,
                   analysis.errorMessage = null,
                   analysis.analyzedAt = null,
                   analysis.nextAttemptAt = null,
                   analysis.claimedBy = null,
                   analysis.claimedAt = null,
                   analysis.updatedAt = :now
             where analysis.status in :inFlight
               and analysis.pullRequest.repository.project.id = :projectId
            """)
    int cancelByProject(@Param("projectId") Long projectId,
                        @Param("cancelled") PullRequestAnalysisStatus cancelled,
                        @Param("inFlight") Collection<PullRequestAnalysisStatus> inFlight,
                        @Param("reason") SummaryFailureCode reason,
                        @Param("now") OffsetDateTime now);

    default int cancelInFlightByRepository(Long repositoryId, SummaryFailureCode reason,
                                           OffsetDateTime now) {
        return cancelByRepository(repositoryId, PullRequestAnalysisStatus.CANCELLED,
                List.of(PullRequestAnalysisStatus.PENDING, PullRequestAnalysisStatus.RUNNING),
                reason, now);
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PullRequestAnalysis analysis
               set analysis.status = :cancelled,
                   analysis.summary = null,
                   analysis.changeType = null,
                   analysis.model = null,
                   analysis.errorCode = :reason,
                   analysis.errorMessage = null,
                   analysis.analyzedAt = null,
                   analysis.nextAttemptAt = null,
                   analysis.claimedBy = null,
                   analysis.claimedAt = null,
                   analysis.updatedAt = :now
             where analysis.status in :inFlight
               and analysis.pullRequest.repository.id = :repositoryId
            """)
    int cancelByRepository(@Param("repositoryId") Long repositoryId,
                           @Param("cancelled") PullRequestAnalysisStatus cancelled,
                           @Param("inFlight") Collection<PullRequestAnalysisStatus> inFlight,
                           @Param("reason") SummaryFailureCode reason,
                           @Param("now") OffsetDateTime now);

    default int cancelInFlightByRequester(Long requesterId, SummaryFailureCode reason,
                                          OffsetDateTime now) {
        return cancelByRequester(requesterId, PullRequestAnalysisStatus.CANCELLED,
                List.of(PullRequestAnalysisStatus.PENDING, PullRequestAnalysisStatus.RUNNING),
                reason, now);
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PullRequestAnalysis analysis
               set analysis.status = :cancelled,
                   analysis.summary = null,
                   analysis.changeType = null,
                   analysis.model = null,
                   analysis.errorCode = :reason,
                   analysis.errorMessage = null,
                   analysis.analyzedAt = null,
                   analysis.nextAttemptAt = null,
                   analysis.claimedBy = null,
                   analysis.claimedAt = null,
                   analysis.updatedAt = :now
             where analysis.status in :inFlight
               and analysis.requestedBy.id = :requesterId
            """)
    int cancelByRequester(@Param("requesterId") Long requesterId,
                          @Param("cancelled") PullRequestAnalysisStatus cancelled,
                          @Param("inFlight") Collection<PullRequestAnalysisStatus> inFlight,
                          @Param("reason") SummaryFailureCode reason,
                          @Param("now") OffsetDateTime now);
}

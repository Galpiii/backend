package com.github.galpiii.galpi.domain.pullrequest.service;

import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.CollectedPullRequest;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.util.LogSafe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 요약 행의 상태를 바꾸는 짧은 트랜잭션들.
 *
 * <p>워커의 실행 자체는 트랜잭션 밖에서 돈다. 요약 하나에 GitHub 호출과 LLM 호출이 하나씩
 * 들어가고 그 대부분이 응답 대기라, 전체를 감싸면 DB 커넥션이 그만큼 묶인다.
 * {@code AnalysisRunWriter}가 저장소 단위로 하는 일을 여기서는 PR 단위로 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullRequestAnalysisWriter {

    private final PullRequestAnalysisRepository analysisRepository;
    private final PullRequestRepository pullRequestRepository;
    private final UserRepository userRepository;

    /**
     * 수집한 PR을 요약 큐에 넣는다.
     *
     * <p>같은 PR을 다시 수집해도 행이 늘지 않는다 -- {@code uk_pull_request_analyses_pull_request}가
     * 있고, 여기서 먼저 기존 행을 찾아 갱신한다.
     *
     * <p>이미 있는 행을 어떻게 할지는 {@code head_sha} 하나로 정한다.
     *
     * <ul>
     *   <li>달라졌으면 지난 요약은 다른 커밋의 것이므로 버리고 다시 큐에 넣는다</li>
     *   <li>같으면 결과와 상태는 <b>건드리지 않는다.</b> 끝난 요약을 다시 만들면 LLM 비용만
     *       다시 쓰고 결과는 같다. 다만 App 재설치를 반영하도록 installation과 요청자는
     *       최신 수집 값으로 갱신한다</li>
     * </ul>
     *
     * <p>조회를 PR 하나씩 하지 않는다. 저장소당 300건이면 그대로 SELECT 300번이 된다.
     */
    @Transactional
    public QueueResult enqueue(Long installationId, Long requestedBy,
                               List<CollectedPullRequest> pullRequests) {
        List<Long> pullRequestIds = pullRequests.stream()
                .map(CollectedPullRequest::pullRequestId)
                .filter(Objects::nonNull)
                .toList();
        if (pullRequestIds.isEmpty()) {
            return QueueResult.EMPTY;
        }

        Map<Long, PullRequestAnalysis> existing = new HashMap<>();
        for (PullRequestAnalysis analysis : analysisRepository
                .findAllByPullRequestIdIn(pullRequestIds)) {
            existing.put(analysis.getPullRequest().getId(), analysis);
        }

        // 참조만 필요하다. 인계 한 번에 사용자는 하나뿐이라 프록시 하나를 돌려 쓴다.
        User requester = userRepository.getReferenceById(requestedBy);
        List<PullRequestAnalysis> created = new ArrayList<>();
        int requeued = 0;
        int kept = 0;

        for (CollectedPullRequest collected : pullRequests) {
            if (collected.pullRequestId() == null) {
                continue;
            }
            PullRequestAnalysis analysis = existing.get(collected.pullRequestId());
            if (analysis == null) {
                PullRequest reference =
                        pullRequestRepository.getReferenceById(collected.pullRequestId());
                created.add(PullRequestAnalysis.pending(reference, installationId, requester,
                        collected.headSha()));
                continue;
            }
            if (!Objects.equals(analysis.getHeadSha(), collected.headSha())) {
                analysis.requeueForNewHead(installationId, requester, collected.headSha());
                requeued++;
                continue;
            }
            if (analysis.getStatus() == PullRequestAnalysisStatus.CANCELLED) {
                // 정상 수집이 다시 여기까지 왔다는 것 자체가 프로젝트·저장소·GitHub 연결이
                // 복구됐다는 증거다. 같은 head라도 취소 상태를 유지하면 영영 다시 돌지 않는다.
                analysis.requeueAfterCancellation(
                        installationId, requester, collected.headSha());
                requeued++;
                continue;
            }
            // 결과를 다시 만들 필요는 없지만 실행 자격은 최신 수집의 검증값으로 바꾼다.
            // App 재설치 뒤에도 옛 installation을 유지하면 사용자 재시도가 영원히 실패한다.
            analysis.refreshExecutionContext(installationId, requester);
            kept++;
        }

        analysisRepository.saveAll(created);
        return new QueueResult(created.size(), requeued, kept);
    }

    @Transactional
    public boolean complete(Long analysisId, String workerId, String summary,
                            ChangeType changeType, String model) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated = analysisRepository.completeClaimed(analysisId, workerId,
                PullRequestAnalysisStatus.RUNNING, PullRequestAnalysisStatus.COMPLETED,
                summary, changeType, model, now);
        return appliedOrLogStale(updated, analysisId, workerId, "complete");
    }

    /**
     * 요약 하나를 실패로 끝낸다.
     *
     * <p>{@code errorMessage}에 예외 메시지를 그대로 넣지 않는다. GitHub 응답 본문이나 URL이
     * 섞이면 토큰이 DB에 남을 수 있어, 마스킹을 거친 값만 저장한다.
     */
    @Transactional
    public boolean fail(Long analysisId, String workerId, SummaryFailureCode errorCode,
                        String errorMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated = analysisRepository.failClaimed(analysisId, workerId,
                PullRequestAnalysisStatus.RUNNING, PullRequestAnalysisStatus.FAILED,
                errorCode, LogSafe.text(errorMessage), now);
        return appliedOrLogStale(updated, analysisId, workerId, "fail");
    }

    @Transactional
    public boolean cancel(Long analysisId, String workerId, SummaryFailureCode reason) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated = analysisRepository.cancelClaimed(analysisId, workerId,
                PullRequestAnalysisStatus.RUNNING, PullRequestAnalysisStatus.CANCELLED,
                reason, now);
        return appliedOrLogStale(updated, analysisId, workerId, "cancel");
    }

    /** 일시 실패를 백오프 뒤 다시 시도한다. 시도 횟수는 유지한다. */
    @Transactional
    public boolean deferForRetry(Long analysisId, String workerId, OffsetDateTime retryAt) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated = analysisRepository.deferClaimedForRetry(
                analysisId, workerId, retryAt, now);
        return appliedOrLogStale(updated, analysisId, workerId, "defer-retry");
    }

    /**
     * rate limit 해제 시각까지 미루고, 선점하며 증가시킨 시도 횟수는 되돌린다.
     *
     * <p>시도 횟수를 되돌리므로 이 경로만 반복되는 PR은 시도 상한에 닿지 않는다. 의도한
     * 것이다 -- 한도는 시간이 지나면 회복되고, 그때까지 못 돌린 것을 "분석 실패"로 굳히면
     * 사용자가 다시 눌러야 할 이유가 없는 것을 다시 누르게 된다. 대신 {@code next_attempt_at}
     * 덕분에 그 사이 폴링 비용은 들지 않는다.
     */
    @Transactional
    public boolean deferForRateLimit(Long analysisId, String workerId,
                                     OffsetDateTime retryAt) {
        OffsetDateTime now = OffsetDateTime.now();
        int updated = analysisRepository.deferClaimedForRateLimit(
                analysisId, workerId, retryAt, now);
        return appliedOrLogStale(updated, analysisId, workerId, "defer");
    }

    private boolean appliedOrLogStale(int updated, Long analysisId, String workerId,
                                      String transition) {
        if (updated == 1) {
            return true;
        }
        // lease를 잃었거나 head 재수집으로 상태가 바뀐 정상 경합이다. 늦은 결과를 버린다.
        log.info("[요약] 소유권을 잃은 워커의 상태 전이를 무시한다 analysisId={} worker={} "
                + "transition={}", analysisId, workerId, transition);
        return false;
    }

    /**
     * @param created  새로 만든 요약 행
     * @param requeued head가 바뀌어 다시 큐에 넣은 행
     * @param kept     기준 커밋이 그대로라 손대지 않은 행
     */
    public record QueueResult(int created, int requeued, int kept) {

        static final QueueResult EMPTY = new QueueResult(0, 0, 0);

        public int queued() {
            return created + requeued;
        }
    }
}

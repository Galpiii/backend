package com.github.galpiii.galpi.domain.pullrequest.worker;

import com.github.galpiii.galpi.domain.collection.entity.Contributor;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.repository.ContributorRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.pullrequest.PullRequestFixture;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.pullrequest.service.PullRequestAnalysisWriter;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요약 선점을 실제 Postgres에 붙여 확인한다.
 *
 * <p>{@code SKIP LOCKED}는 H2나 mock으로는 검증할 수 없다. 이 테스트가 없으면 워커를 두 개
 * 띄웠을 때 같은 PR이 두 번 요약되는지 알 방법이 없고, 그건 GitHub 호출과 LLM 비용을 두 배로
 * 쓰면서 서로의 결과를 덮는 상황이다.
 *
 * <p>작업 단위로 하나씩 집는 {@code AnalysisRunClaimIntegrationTest}와 달리 여기서는 배치로
 * 집는다. 배치가 겹치지 않는지가 이쪽의 핵심이다.
 */
@DisplayName("PR 요약 선점 — 실제 Postgres")
class PullRequestSummaryClaimIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PullRequestSummaryClaimer claimer;
    @Autowired
    private PullRequestAnalysisRepository analysisRepository;
    @Autowired
    private PullRequestAnalysisWriter writer;
    @Autowired
    private PullRequestRepository pullRequestRepository;
    @Autowired
    private ContributorRepository contributorRepository;
    @Autowired
    private GithubRepositoryRepository repositoryRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private GithubRepository repository;
    private Contributor contributor;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(PullRequestFixture.user("wb"));
        Project project = projectRepository.save(Project.create(owner, "갈피"));
        repository = repositoryRepository.save(
                PullRequestFixture.repository(project, "sample-org/backend"));
        contributor = contributorRepository.save(
                PullRequestFixture.contributor(project, "developerA"));
    }

    @Test
    @DisplayName("PENDING 요약을 선점하면 RUNNING이 되고 워커 식별자와 시도 횟수가 남는다")
    void claimsPendingAnalysis() {
        Long analysisId = queue(41);

        List<Long> claimed = claimer.claim("worker-1");

        assertThat(claimed).containsExactly(analysisId);
        PullRequestAnalysis analysis = analysisRepository.findById(analysisId).orElseThrow();
        assertThat(analysis.getStatus()).isEqualTo(PullRequestAnalysisStatus.RUNNING);
        assertThat(analysis.getClaimedBy()).isEqualTo("worker-1");
        assertThat(analysis.getClaimedAt()).isNotNull();
        assertThat(analysis.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("워커 두 개가 동시에 집어도 같은 요약을 두 번 집지 않는다")
    void doesNotClaimSameAnalysisTwice() throws Exception {
        List<Long> queued = List.of(queue(41), queue(42), queue(43), queue(44));

        List<List<Long>> results = runConcurrently(
                () -> claimer.claim("worker-1"),
                () -> claimer.claim("worker-2"));

        List<Long> all = results.stream().flatMap(List::stream).toList();
        assertThat(all).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(queued);
        // 두 번 집혔다면 attempts가 2가 된다.
        assertThat(analysisRepository.findAllById(queued))
                .allSatisfy(analysis -> assertThat(analysis.getAttempts()).isEqualTo(1));
    }

    @Test
    @DisplayName("배치 크기까지만 집는다")
    void claimsUpToBatchSize() {
        for (int number = 1; number <= 15; number++) {
            queue(number);
        }

        assertThat(claimer.claim("worker-1")).hasSize(10);
    }

    @Test
    @DisplayName("이미 RUNNING인 요약은 선점하지 않는다")
    void skipsRunningAnalysis() {
        queue(41);
        claimer.claim("worker-1");

        assertThat(claimer.claim("worker-2")).isEmpty();
    }

    @Test
    @DisplayName("lease가 지난 RUNNING 요약은 다른 워커가 다시 선점한다")
    void reclaimsStaleAnalysis() {
        Long analysisId = queue(41);
        claimer.claim("dead-worker");
        expireLease(analysisId, 11);

        assertThat(claimer.claim("recovery-worker")).containsExactly(analysisId);
        PullRequestAnalysis reclaimed = analysisRepository.findById(analysisId).orElseThrow();
        assertThat(reclaimed.getClaimedBy()).isEqualTo("recovery-worker");
        assertThat(reclaimed.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("lease를 잃은 워커의 늦은 완료가 새 소유자의 결과를 덮지 않는다")
    void rejectsCompletionFromStaleWorker() {
        Long analysisId = queue(41);
        claimer.claim("worker-1");
        expireLease(analysisId, 11);
        claimer.claim("worker-2");

        assertThat(writer.complete(
                analysisId, "worker-1", "오래된 결과", ChangeType.FEATURE, "test-model"))
                .isFalse();

        PullRequestAnalysis running = analysisRepository.findById(analysisId).orElseThrow();
        assertThat(running.getStatus()).isEqualTo(PullRequestAnalysisStatus.RUNNING);
        assertThat(running.getClaimedBy()).isEqualTo("worker-2");
        assertThat(running.getSummary()).isNull();

        assertThat(writer.complete(
                analysisId, "worker-2", "최신 결과", ChangeType.BUGFIX, "test-model"))
                .isTrue();
        PullRequestAnalysis completed = analysisRepository.findById(analysisId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PullRequestAnalysisStatus.COMPLETED);
        assertThat(completed.getSummary()).isEqualTo("최신 결과");
    }

    @Test
    @DisplayName("rate limit으로 미룬 요약은 시도 횟수를 소비하지 않고 예약 시각 뒤에만 선점한다")
    void defersRateLimitedAnalysisWithoutConsumingAttempt() {
        Long analysisId = queue(41);
        claimer.claim("worker-1");
        OffsetDateTime retryAt = OffsetDateTime.now().plusMinutes(10);

        assertThat(writer.deferForRateLimit(analysisId, "worker-1", retryAt)).isTrue();

        PullRequestAnalysis deferred = analysisRepository.findById(analysisId).orElseThrow();
        assertThat(deferred.getStatus()).isEqualTo(PullRequestAnalysisStatus.PENDING);
        assertThat(deferred.getAttempts()).isZero();
        assertThat(deferred.getNextAttemptAt())
                .isBetween(retryAt.minusSeconds(1), retryAt.plusSeconds(1));
        assertThat(claimer.claim("worker-2")).isEmpty();

        jdbcTemplate.update("""
                update pull_request_analyses
                   set next_attempt_at = now() - interval '1 second'
                 where id = ?
                """, analysisId);

        assertThat(claimer.claim("worker-2")).containsExactly(analysisId);
        assertThat(analysisRepository.findById(analysisId).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("현재 소유 워커의 heartbeat만 lease를 연장한다")
    void renewsLeaseOnlyForOwner() {
        Long analysisId = queue(41);
        claimer.claim("worker-1");
        expireLease(analysisId, 5);

        assertThat(claimer.heartbeat(List.of(analysisId), "worker-2")).isFalse();
        assertThat(claimer.heartbeat(List.of(analysisId), "worker-1")).isTrue();
        assertThat(analysisRepository.findById(analysisId).orElseThrow().getClaimedAt())
                .isAfter(OffsetDateTime.now().minusMinutes(1));
    }

    @Test
    @DisplayName("COMPLETED와 FAILED는 선점 대상이 아니다")
    void ignoresFinishedAnalyses() {
        queue(41);
        List<Long> claimed = claimer.claim("worker-1");
        jdbcTemplate.update("""
                update pull_request_analyses
                   set status = 'COMPLETED', summary = '요약', change_type = 'FEATURE',
                       claimed_by = null, claimed_at = null
                 where id = ?
                """, claimed.get(0));

        assertThat(claimer.claim("worker-2")).isEmpty();
    }

    @Test
    @DisplayName("집을 것이 없으면 빈 목록을 돌려준다")
    void returnsEmptyWhenNothingQueued() {
        assertThat(claimer.claim("worker-1")).isEmpty();
    }

    private Long queue(int number) {
        PullRequest pullRequest = pullRequestRepository.save(
                PullRequestFixture.pullRequest(repository, contributor, number, "feat: " + number));
        return analysisRepository.save(PullRequestAnalysis.pending(
                pullRequest, 5000L, owner, pullRequest.getHeadSha())).getId();
    }

    private void expireLease(Long analysisId, int minutes) {
        jdbcTemplate.update("""
                update pull_request_analyses
                   set claimed_at = now() - make_interval(mins => ?)
                 where id = ?
                """, minutes, analysisId);
    }

    /**
     * 두 선점을 같은 순간에 시작시킨다.
     *
     * <p>{@code CountDownLatch}로 출발을 맞추지 않으면 한쪽이 먼저 끝나 버려 경합이 일어나지
     * 않고, 잠금이 없어도 테스트가 통과한다.
     */
    private static List<List<Long>> runConcurrently(Callable<List<Long>> first,
                                                    Callable<List<Long>> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> left = executor.submit(awaiting(start, first));
            Future<List<Long>> right = executor.submit(awaiting(start, second));
            start.countDown();
            return List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Callable<List<Long>> awaiting(CountDownLatch start, Callable<List<Long>> task) {
        return () -> {
            start.await();
            return task.call();
        };
    }
}

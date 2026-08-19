package com.github.galpiii.galpi.domain.analysis.worker;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunTargetRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업 선점을 실제 Postgres에 붙여 확인한다.
 *
 * <p>{@code SKIP LOCKED}는 H2나 mock으로는 검증할 수 없다. 이 테스트가 없으면 워커를 두 개
 * 띄웠을 때 같은 작업이 두 번 도는지 알 방법이 없고, 그건 GitHub 호출을 두 배로 쓰면서
 * 서로의 결과를 덮는 상황이다.
 *
 * <p>덤으로 V5 마이그레이션과 엔티티 매핑이 맞는지도 여기서 걸린다 —
 * {@code IntegrationTestSupport}가 Flyway를 켜고 {@code ddl-auto=validate}로 띄운다.
 */
@DisplayName("분석 작업 선점 — 실제 Postgres")
class AnalysisRunClaimIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private AnalysisRunClaimer claimer;
    @Autowired
    private AnalysisRunRepository runRepository;
    @Autowired
    private AnalysisRunTargetRepository targetRepository;
    @Autowired
    private GithubRepositoryRepository repositoryRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;

    private Project project;

    @BeforeEach
    void setUp() {
        targetRepository.deleteAll();
        runRepository.deleteAll();
        repositoryRepository.deleteAll();
        projectRepository.deleteAll();

        User user = userRepository.save(
                User.ofGithub(System.nanoTime(), "wb", "wb", null, "https://avatar"));
        project = projectRepository.save(Project.create(user, "갈피"));
    }

    @Test
    @DisplayName("QUEUED 작업을 선점하면 RUNNING이 되고 워커 식별자가 남는다")
    void claimsQueuedRun() {
        Long runId = queueRun();

        Optional<Long> claimed = claimer.claim("worker-1");

        assertThat(claimed).contains(runId);
        AnalysisRun run = runRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(AnalysisRunStatus.RUNNING);
        assertThat(run.getClaimedBy()).isEqualTo("worker-1");
        assertThat(run.getClaimedAt()).isNotNull();
        assertThat(run.getAttempts()).isEqualTo(1);
        assertThat(run.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("서버를 재시작해도 QUEUED로 남아 있던 작업을 다시 선점한다")
    void reclaimsRunAfterRestart() {
        // 재시작은 "이 프로세스가 아직 아무것도 집지 않은 상태"와 같다. QUEUED 행이 그대로
        // 남아 있으면 새 워커가 집어 간다.
        Long runId = queueRun();

        assertThat(claimer.claim("worker-after-restart")).contains(runId);
    }

    @Test
    @DisplayName("워커 두 개가 동시에 집어도 한쪽만 성공한다")
    void doesNotClaimSameRunTwice() throws Exception {
        Long runId = queueRun();

        List<Optional<Long>> results = runConcurrently(
                () -> claimer.claim("worker-1"),
                () -> claimer.claim("worker-2"));

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(results).filteredOn(Optional::isPresent)
                .allSatisfy(result -> assertThat(result).contains(runId));
        assertThat(runRepository.findById(runId).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("작업이 둘이면 워커 둘이 서로 다른 작업을 집는다")
    void distributesRunsAcrossWorkers() throws Exception {
        Long first = queueRun();
        Long second = queueRun();

        List<Optional<Long>> results = runConcurrently(
                () -> claimer.claim("worker-1"),
                () -> claimer.claim("worker-2"));

        assertThat(results).filteredOn(Optional::isPresent).hasSize(2);
        assertThat(results.stream().flatMap(Optional::stream).toList())
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("이미 RUNNING인 작업은 선점하지 않는다")
    void skipsRunningRun() {
        Long runId = queueRun();
        claimer.claim("worker-1");

        assertThat(claimer.claim("worker-2")).isEmpty();
        assertThat(runRepository.findById(runId).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("집을 작업이 없으면 빈 값을 돌려준다")
    void returnsEmptyWhenNothingQueued() {
        assertThat(claimer.claim("worker-1")).isEmpty();
    }

    private Long queueRun() {
        return runRepository.save(
                AnalysisRun.queue(project, project.getUser(), Map.of(1L, 100L))).getId();
    }

    /**
     * 두 선점을 같은 순간에 시작시킨다.
     *
     * <p>{@code CountDownLatch}로 출발을 맞추지 않으면 한쪽이 먼저 끝나 버려 경합이 일어나지
     * 않고, 잠금이 없어도 테스트가 통과한다.
     */
    private static List<Optional<Long>> runConcurrently(Callable<Optional<Long>> first,
                                                        Callable<Optional<Long>> second)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<Long>> left = executor.submit(awaiting(start, first));
            Future<Optional<Long>> right = executor.submit(awaiting(start, second));
            start.countDown();
            return List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Callable<Optional<Long>> awaiting(CountDownLatch start,
                                                     Callable<Optional<Long>> task) {
        return () -> {
            start.await();
            return task.call();
        };
    }
}

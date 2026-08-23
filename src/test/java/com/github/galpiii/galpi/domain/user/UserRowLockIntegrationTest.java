package com.github.galpiii.galpi.domain.user;

import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.analysis.service.AnalysisRunService;
import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import com.github.galpiii.galpi.domain.consent.service.AiDataConsentService;
import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 사용자 행 잠금이 실제로 분석 생성을 줄 세우는지 확인한다.
 *
 * <p>상태만 확인하는 테스트로는 이걸 볼 수 없다. 잠금을 지우고 단순 상태 검사만 남겨도 순차
 * 실행 테스트는 그대로 통과한다. 그래서 다른 트랜잭션이 잠금을 <b>쥐고 있는 동안</b> 생성이
 * 기다리는지를 본다.
 *
 * <p>생성 경로를 고른 이유가 있다. 연결 해제는 잠금이 없어도 같은 행을 UPDATE하느라 어차피
 * 기다려서, 잠금이 있든 없든 결과가 같다 — 그 경로로는 잠금의 효과를 증명할 수 없다. 반면
 * 생성은 사용자 행을 읽기만 하므로, 명시적 잠금이 없으면 그대로 지나간다.
 *
 * <p>"제한 시간 안에 끝나지 않았다"는 단정은 한 방향으로만 흔들린다. 잠금이 동작하면 대기하는
 * 쪽은 풀릴 때까지 끝나지 않아 거짓 실패가 날 수 없고, 잠금이 없으면 즉시 끝나 실패한다.
 * {@code pg_locks}로 실제 대기 중인 잠금까지 확인해 "그냥 느린 것"과 구분한다.
 *
 * <p>커넥션 풀을 늘리는 이유는 이 테스트가 동시에 셋(잠금 보유·대기·관찰)을 쓰기 때문이다.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=6")
@DisplayName("사용자 행 잠금 — 실제 Postgres")
class UserRowLockIntegrationTest extends IntegrationTestSupport {

    private static final long INSTALLATION_ID = 100L;
    private static final long GITHUB_REPOSITORY_ID = 555L;

    @Autowired
    private AnalysisRunService analysisRunService;
    @Autowired
    private AiDataConsentService consentService;
    @Autowired
    private ConsentProperties consentProperties;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private GithubRepositoryRepository repositoryRepository;
    @Autowired
    private AnalysisRunRepository runRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private GithubApiClient apiClient;
    @MockitoBean
    private GithubInstallationService installationService;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    private User user;
    private Long projectId;

    @BeforeEach
    void setUp() {
        user = userRepository.save(connectedUser());
        Project project = projectRepository.save(Project.create(user, "갈피"));
        projectId = project.getId();
        repositoryRepository.save(GithubRepository.link(project, snapshot()));
        consentService.agree(user.getId(), consentProperties.aiDataVersion());
        given(installationService.accessibleSnapshots(anyLong(), any()))
                .willReturn(Map.of(GITHUB_REPOSITORY_ID, snapshot()));
    }

    @AfterEach
    void tearDown() {
        threads.shutdownNow();
    }

    @Test
    @DisplayName("사용자 행 잠금을 쥐고 있는 동안 분석 생성은 기다린다")
    void creationWaitsForTheUserRowLock() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = threads.submit(() -> holdLock(acquired, release));
        assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();

        Future<?> creation = threads.submit(
                () -> analysisRunService.create(user.getId(), projectId));
        try {
            // 잠금이 동작하면 이쪽은 풀릴 때까지 끝나지 않는다. 끝났다면 잠그지 않은 것이다.
            assertThatThrownBy(() -> creation.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            // 느린 것이 아니라 정말 잠금을 기다리는 중인지 DB에 직접 묻는다.
            assertThat(waitingLocks()).isPositive();
            // 기다리는 동안에는 아무것도 만들어지지 않는다.
            assertThat(runRepository.findAll()).isEmpty();
        } finally {
            release.countDown();
        }

        creation.get(10, TimeUnit.SECONDS);
        holder.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("잠금이 풀리면 이어서 만든다 — 대기지 실패가 아니다")
    void proceedsOnceTheLockIsReleased() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        threads.submit(() -> holdLock(acquired, release));
        assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();

        Future<?> creation = threads.submit(
                () -> analysisRunService.create(user.getId(), projectId));
        release.countDown();

        creation.get(10, TimeUnit.SECONDS);
        assertThat(runRepository.findAll()).hasSize(1);
    }

    private Object holdLock(CountDownLatch acquired, CountDownLatch release) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            userRepository.findByIdForUpdate(user.getId()).orElseThrow();
            acquired.countDown();
            await(release);
            return null;
        });
    }

    /** 아직 부여되지 않은 잠금. 누군가 행 잠금을 기다리고 있으면 0보다 크다. */
    private int waitingLocks() {
        Integer waiting = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class);
        return waiting == null ? 0 : waiting;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static User connectedUser() {
        User user = User.ofGithub(System.nanoTime(), "wb", "wb", null, "https://avatar");
        user.connectGithub();
        return user;
    }

    private static RepositorySnapshot snapshot() {
        return new RepositorySnapshot(GITHUB_REPOSITORY_ID, INSTALLATION_ID, "galpiii", "backend",
                "galpiii/backend", true, "main", "https://github.com/galpiii/backend");
    }
}

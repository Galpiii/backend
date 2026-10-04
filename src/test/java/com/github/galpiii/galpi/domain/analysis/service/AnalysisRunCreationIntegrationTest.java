package com.github.galpiii.galpi.domain.analysis.service;

import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import com.github.galpiii.galpi.domain.consent.service.AiDataConsentService;
import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.github.service.GithubConnectionService;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.OAuthProvider;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.entity.UserOAuthToken;
import com.github.galpiii.galpi.domain.user.repository.UserOAuthTokenRepository;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 분석 작업 생성이 "지금 연결되어 있다"를 근거로만 이뤄지는지 확인한다.
 *
 * <p>생성은 GitHub 왕복을 포함해 몇 초가 걸리고, 그 사이에 연결이 끊기면 취소 쿼리가 이
 * 작업보다 먼저 지나갈 수 있다. 그러면 근거가 사라진 뒤에도 워커가 installation token으로
 * 실행할 수 있는 작업이 남는다. 생성 트랜잭션이 사용자 행을 잠그고 상태를 다시 보는 것은
 * 그 순서를 만들지 않기 위해서다.
 */
@DisplayName("분석 작업 생성 — 연결 상태와의 경합")
class AnalysisRunCreationIntegrationTest extends IntegrationTestSupport {

    private static final long INSTALLATION_ID = 100L;
    private static final long GITHUB_REPOSITORY_ID = 555L;
    private static final String TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz012345";

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private AnalysisRunService analysisRunService;
    @Autowired
    private GithubConnectionService connectionService;
    @Autowired
    private AiDataConsentService consentService;
    @Autowired
    private ConsentProperties consentProperties;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserOAuthTokenRepository tokenRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private GithubRepositoryRepository repositoryRepository;
    @Autowired
    private AnalysisRunRepository runRepository;
    @Autowired
    private TokenCipher tokenCipher;

    @MockitoBean
    private GithubApiClient apiClient;
    @MockitoBean
    private GithubInstallationService installationService;

    private User user;
    private Long projectId;

    @BeforeEach
    void setUp() {
        user = userRepository.save(connectedUser());
        Project project = projectRepository.save(Project.create(user, "갈피"));
        projectId = project.getId();
        repositoryRepository.save(GithubRepository.link(project, snapshot()));

        tokenRepository.saveAndFlush(UserOAuthToken.issue(user, OAuthProvider.GITHUB,
                tokenCipher.encrypt(TOKEN), OffsetDateTime.now().plus(Duration.ofHours(8)),
                tokenCipher.currentVersion()));
        consentService.agree(user.getId(), consentProperties.aiDataVersion());
        given(installationService.accessibleSnapshots(anyLong(), any()))
                .willReturn(Map.of(GITHUB_REPOSITORY_ID, snapshot()));
    }

    @Test
    @DisplayName("연결된 상태에서는 그대로 만들어진다")
    void createsWhileConnected() {
        assertThat(analysisRunService.create(user.getId(), projectId).analysisRunId()).isNotNull();
    }

    @Test
    @DisplayName("연결이 끊긴 뒤에는 새 작업을 만들지 않는다 — 취소와 생성이 엇갈릴 자리를 없앤다")
    void blocksCreationWhileDisconnected() {
        connectionService.disconnect(user.getId());

        assertThatThrownBy(() -> analysisRunService.create(user.getId(), projectId))
                .isInstanceOf(GithubReauthRequiredException.class);

        // 하나라도 남으면 워커가 그것을 집어 권한 근거 없이 수집한다.
        assertThat(runRepository.findAll()).isEmpty();
    }

    @Test
    void addingFrontendKeepsBackendCompletedAcrossFreshStatusReads() {
        Long firstId = analysisRunService.create(user.getId(), projectId).analysisRunId();
        var firstRun = runRepository.findById(firstId).orElseThrow();
        firstRun.finish(com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus.COMPLETED);
        runRepository.saveAndFlush(firstRun);
        // Simulate the worker's completed target without making external GitHub/AI calls.
        jdbcTemplate.update("update analysis_run_repositories set status = 'COMPLETED' where analysis_run_id = ?", firstId);
        var project = projectRepository.findById(projectId).orElseThrow();
        var frontendSnapshot = new RepositorySnapshot(777L, INSTALLATION_ID, "galpiii", "frontend",
                "galpiii/frontend", true, "main", "https://github.com/galpiii/frontend");
        var frontend = repositoryRepository.saveAndFlush(GithubRepository.link(project, frontendSnapshot));
        given(installationService.accessibleSnapshots(anyLong(), any()))
                .willReturn(Map.of(GITHUB_REPOSITORY_ID, snapshot(), 777L, frontendSnapshot));
        Long secondId = analysisRunService.create(user.getId(), projectId, java.util.List.of(frontend.getId())).analysisRunId();
        assertThat(analysisRunService.get(user.getId(), secondId).repositories())
                .extracting(r -> r.repositoryId()).containsExactly(frontend.getId());
        for (int i = 0; i < 2; i++) {
            var statuses = analysisRunService.repositoryStatuses(user.getId(), projectId);
            assertThat(statuses).hasSize(2);
            assertThat(statuses).filteredOn(r -> r.analysisRunId().equals(firstId))
                    .extracting(r -> r.status()).containsExactly("COMPLETED");
            assertThat(statuses).filteredOn(r -> r.repositoryId().equals(frontend.getId()))
                    .extracting(r -> r.status()).containsExactly("PENDING");
        }
    }

    @ParameterizedTest
    @CsvSource({"FAILED,PENDING", "FAILED,COLLECTING", "CANCELLED,PENDING",
            "CANCELLED,COLLECTING", "RATE_LIMITED,PENDING", "RATE_LIMITED,COLLECTING"})
    void stoppedRunOverridesOnlyUnfinishedTargets(AnalysisRunStatus status, String targetStatus) {
        Long runId = analysisRunService.create(user.getId(), projectId).analysisRunId();
        jdbcTemplate.update("update analysis_run_repositories set status = ? where analysis_run_id = ?",
                targetStatus, runId);
        var run = runRepository.findById(runId).orElseThrow();
        if (status == AnalysisRunStatus.FAILED) {
            run.fail("TEST_FAILURE", "attempt limit exceeded");
        } else if (status == AnalysisRunStatus.RATE_LIMITED) {
            run.markRateLimited(OffsetDateTime.now().plusMinutes(1));
        } else {
            run.finish(status);
        }
        runRepository.saveAndFlush(run);

        assertThat(analysisRunService.repositoryStatuses(user.getId(), projectId))
                .singleElement().satisfies(response -> {
                    assertThat(response.analysisRunId()).isEqualTo(runId);
                    assertThat(response.status()).isEqualTo(status.name());
                });

        jdbcTemplate.update("update analysis_run_repositories set status = 'COMPLETED' where analysis_run_id = ?",
                runId);
        assertThat(analysisRunService.repositoryStatuses(user.getId(), projectId))
                .singleElement().satisfies(response -> assertThat(response.status()).isEqualTo("COMPLETED"));
    }

    @Test
    void includesUnanalyzedRepositoriesAlongsideLatestTargetsAndExcludesUnlinkedOnes() {
        var backend = repositoryRepository.findAllByProjectId(projectId).getFirst();
        var project = projectRepository.findById(projectId).orElseThrow();
        var frontendSnapshot = new RepositorySnapshot(777L, INSTALLATION_ID, "galpiii", "frontend",
                "galpiii/frontend", true, "main", "https://github.com/galpiii/frontend");
        var frontend = repositoryRepository.saveAndFlush(GithubRepository.link(project, frontendSnapshot));

        var initial = analysisRunService.repositoryStatuses(user.getId(), projectId);
        assertThat(initial).extracting(response -> response.repositoryId())
                .containsExactly(backend.getId(), frontend.getId());
        assertThat(initial).allSatisfy(response -> {
            assertThat(response.status()).isEqualTo("NOT_ANALYZED");
            assertThat(response.analysisRunId()).isNull();
            assertThat(response.incompleteReasons()).isEmpty();
        });

        Long runId = analysisRunService.create(user.getId(), projectId, java.util.List.of(backend.getId()))
                .analysisRunId();
        var mixed = analysisRunService.repositoryStatuses(user.getId(), projectId);
        assertThat(mixed).hasSize(2);
        assertThat(mixed.getFirst().analysisRunId()).isEqualTo(runId);
        assertThat(mixed.getFirst().status()).isEqualTo("PENDING");
        assertThat(mixed.getLast().status()).isEqualTo("NOT_ANALYZED");
        assertThat(mixed.getLast().analysisRunId()).isNull();

        backend.unlink();
        repositoryRepository.saveAndFlush(backend);
        assertThat(analysisRunService.repositoryStatuses(user.getId(), projectId))
                .singleElement().satisfies(response -> assertThat(response.repositoryId()).isEqualTo(frontend.getId()));
        frontend.unlink();
        repositoryRepository.saveAndFlush(frontend);
        assertThat(analysisRunService.repositoryStatuses(user.getId(), projectId)).isEmpty();
    }

    private static RepositorySnapshot snapshot() {
        return new RepositorySnapshot(GITHUB_REPOSITORY_ID, INSTALLATION_ID, "galpiii", "backend",
                "galpiii/backend", true, "main", "https://github.com/galpiii/backend");
    }

    /** 연결은 토큰 저장과 함께 확정된다. 픽스처는 그 결과 상태를 직접 만든다. */
    private static User connectedUser() {
        User user = User.ofGithub(System.nanoTime(), "wb", "wb", null, "https://avatar");
        user.connectGithub();
        return user;
    }

}

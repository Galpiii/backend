package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.analysis.dto.AnalysisRunStatusResponse;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.analysis.service.AnalysisRunService;
import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.dto.GithubDisconnectResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositoryAccessResyncResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.entity.RepositoryAccessStatus;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubTokenRevocationRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.project.service.ProjectRepositoryService;
import com.github.galpiii.galpi.domain.user.entity.GithubConnectionStatus;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 연결 해제와 재연결을 실제 Postgres에 붙여 확인한다.
 *
 * <p>mock으로는 볼 수 없는 것이 여기 모여 있다. 진행 중인 작업 취소는 소유자 기준 벌크
 * 갱신이라 트랜잭션 경계가 필요하고, "연결을 끊어도 남는 것"과 "사라지는 것"의 구분은 여러
 * 테이블에 걸쳐 있어 실제로 지워 봐야 확인된다.
 */
@DisplayName("GitHub 연결 해제 — 실제 Postgres")
class GithubDisconnectIntegrationTest extends IntegrationTestSupport {

    private static final long INSTALLATION_ID = 100L;
    private static final long GITHUB_REPOSITORY_ID = 555L;
    private static final String TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz012345";

    @Autowired
    private GithubConnectionService connectionService;
    @Autowired
    private GithubRepositoryAccessService repositoryAccessService;
    @Autowired
    private ProjectRepositoryService projectRepositoryService;
    @Autowired
    private AnalysisRunService analysisRunService;
    @Autowired
    private GithubUserTokenService userTokenService;
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
    private GithubTokenRevocationRepository revocationRepository;
    @Autowired
    private TokenCipher tokenCipher;

    @MockitoBean
    private GithubApiClient apiClient;
    @MockitoBean
    private GithubInstallationService installationService;

    private User user;
    private Long projectId;
    private Long repositoryId;

    @BeforeEach
    void setUp() {
        user = userRepository.save(
                User.ofGithub(System.nanoTime(), "wb", "wb", null, "https://avatar"));
        Project project = projectRepository.save(Project.create(user, "갈피"));
        projectId = project.getId();
        repositoryId = repositoryRepository.save(
                GithubRepository.link(project, snapshot())).getId();
    }

    @Test
    @DisplayName("유효한 토큰이 있으면 authorization 전체를 폐기한다")
    void revokesGrantWithValidToken() {
        storeToken(Duration.ofHours(8));

        GithubDisconnectResponse response = connectionService.disconnect(user.getId());

        verify(apiClient).revokeUserGrant(TOKEN);
        // 토큰 하나만 지우면 GitHub 쪽에 authorization이 남는다.
        verify(apiClient, never()).revokeUserToken(any());
        assertThat(response.authorizationRevoked()).isTrue();
        assertThat(tokenRepository.findByUserIdAndProvider(user.getId(), OAuthProvider.GITHUB))
                .isEmpty();
        assertThat(userRepository.findById(user.getId()).orElseThrow()
                .getGithubConnectionStatus()).isEqualTo(GithubConnectionStatus.DISCONNECTED);
    }

    @Test
    @DisplayName("토큰이 만료됐으면 재인증을 요구하지 않고 직접 해제할 링크만 안내한다")
    void doesNotForceReauthWhenTokenExpired() {
        storeToken(Duration.ofHours(-1));

        GithubDisconnectResponse response = connectionService.disconnect(user.getId());

        verify(apiClient, never()).revokeUserGrant(any());
        assertThat(response.authorizationRevoked()).isFalse();
        assertThat(response.authorizationsUrl()).contains("/settings/apps/authorizations");
        assertThat(response.installationsUrl()).contains("/settings/installations");
        assertThat(userRepository.findById(user.getId()).orElseThrow()
                .getGithubConnectionStatus()).isEqualTo(GithubConnectionStatus.DISCONNECTED);
    }

    @Test
    @DisplayName("폐기 호출이 실패해도 연결은 끊고 토큰은 재시도 큐에 남긴다")
    void keepsCiphertextForRetryWhenRevokeFails() {
        storeToken(Duration.ofHours(8));
        willThrow(new GithubApiException()).given(apiClient).revokeUserGrant(TOKEN);

        GithubDisconnectResponse response = connectionService.disconnect(user.getId());

        assertThat(response.authorizationRevoked()).isFalse();
        assertThat(revocationRepository.findAll()).singleElement().satisfies(pending ->
                assertThat(pending.getEncryptedAccessToken()).doesNotContain(TOKEN));
    }

    @Test
    @DisplayName("진행 중이던 분석은 즉시 취소된다 — 권한 근거가 사라졌다")
    void cancelsInFlightAnalysis() {
        storeToken(Duration.ofHours(8));
        Long runId = queueRun();

        connectionService.disconnect(user.getId());

        assertThat(runRepository.findById(runId).orElseThrow().getStatus())
                .isEqualTo(AnalysisRunStatus.CANCELLED);
        // 워커도 이 판단을 따라야 한다. 이미 선점한 작업은 저장소마다 이 값을 다시 본다.
        assertThat(runRepository.isAbandoned(runId)).isTrue();
    }

    @Test
    @DisplayName("연결을 끊어도 저장소 연결과 기존 분석 결과는 남고 계속 조회된다")
    void keepsRepositoriesAndPastResults() {
        storeToken(Duration.ofHours(8));
        Long runId = finishedRun();

        connectionService.disconnect(user.getId());

        assertThat(repositoryRepository.findAllByProjectId(projectId)).hasSize(1);
        AnalysisRunStatusResponse status = analysisRunService.get(user.getId(), runId);
        assertThat(status.status()).isEqualTo(AnalysisRunStatus.COMPLETED);
    }

    @Test
    @DisplayName("연결이 끊긴 동안에는 저장소를 뺄 수 없다 — 조회만 허용한다")
    void blocksRepositoryRemovalWhileDisconnected() {
        storeToken(Duration.ofHours(8));
        connectionService.disconnect(user.getId());

        assertThatThrownBy(() ->
                projectRepositoryService.unlink(user.getId(), projectId, repositoryId))
                .isInstanceOf(GithubReauthRequiredException.class);

        assertThat(repositoryRepository.findAllByProjectId(projectId)).hasSize(1);
    }

    @Test
    @DisplayName("재연결하면 github_repository_id 기준으로 접근 상태가 복구된다")
    void restoresRepositoryAccessOnReconnect() {
        repositoryRepository.findById(repositoryId).ifPresent(repository -> {
            repository.markInaccessible();
            repositoryRepository.save(repository);
        });
        given(installationService.accessibleSnapshots(anyLong(), any()))
                .willReturn(Map.of(GITHUB_REPOSITORY_ID, snapshot()));

        RepositoryAccessResyncResponse response =
                repositoryAccessService.resync(user.getId());

        assertThat(response.accessibleCount()).isEqualTo(1);
        assertThat(response.inaccessibleCount()).isZero();
        assertThat(repositoryRepository.findById(repositoryId).orElseThrow().getAccessStatus())
                .isEqualTo(RepositoryAccessStatus.ACCESSIBLE);
    }

    @Test
    @DisplayName("재연결 후에도 보이지 않는 저장소는 지우지 않고 INACCESSIBLE로 남긴다")
    void marksRepositoriesThatDidNotComeBack() {
        given(installationService.accessibleSnapshots(anyLong(), any())).willReturn(Map.of());

        RepositoryAccessResyncResponse response =
                repositoryAccessService.resync(user.getId());

        assertThat(response.inaccessibleCount()).isEqualTo(1);
        assertThat(response.repositories()).singleElement().satisfies(repository ->
                assertThat(repository.accessStatus())
                        .isEqualTo(RepositoryAccessStatus.INACCESSIBLE));
        // 사라진 이유를 화면이 설명할 수 있어야 하므로 행 자체는 남는다.
        assertThat(repositoryRepository.findAllByProjectId(projectId)).hasSize(1);
    }

    /**
     * 원본만 심는다. 조회 캐시를 채우지 않는 것이 요점이다 — 만료된 토큰을 캐시에 넣으면
     * 만료 판정 자체를 건너뛴다.
     */
    private void storeToken(Duration expiresIn) {
        userTokenService.delete(user.getId());
        tokenRepository.saveAndFlush(UserOAuthToken.issue(user, OAuthProvider.GITHUB,
                tokenCipher.encrypt(TOKEN), OffsetDateTime.now().plus(expiresIn),
                tokenCipher.currentVersion()));
    }

    private Long queueRun() {
        Project project = projectRepository.findById(projectId).orElseThrow();
        return runRepository.save(AnalysisRun.queue(project, project.getOwner(),
                Map.of(GITHUB_REPOSITORY_ID, INSTALLATION_ID))).getId();
    }

    private Long finishedRun() {
        Project project = projectRepository.findById(projectId).orElseThrow();
        AnalysisRun run = AnalysisRun.queue(project, project.getOwner(),
                Map.of(GITHUB_REPOSITORY_ID, INSTALLATION_ID));
        run.finish(AnalysisRunStatus.COMPLETED);
        return runRepository.save(run).getId();
    }

    private static RepositorySnapshot snapshot() {
        return new RepositorySnapshot(GITHUB_REPOSITORY_ID, INSTALLATION_ID, "galpiii", "backend",
                "galpiii/backend", true, "main", "https://github.com/galpiii/backend");
    }
}

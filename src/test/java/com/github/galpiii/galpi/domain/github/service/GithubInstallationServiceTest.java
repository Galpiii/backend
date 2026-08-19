package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.client.GithubRequestBudget;
import com.github.galpiii.galpi.domain.github.client.dto.GithubInstallationResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.config.GithubOperationProperties;
import com.github.galpiii.galpi.domain.github.dto.InstallationRepositoriesResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubInstallationUnavailableException;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GithubInstallationService — 설치·저장소 조회")
class GithubInstallationServiceTest {

    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 3L;
    private static final long PERSONAL_INSTALLATION = 100L;
    private static final long ORG_INSTALLATION = 200L;
    private static final String TOKEN = "gho_user_token";

    @Mock
    private GithubApiClient apiClient;
    @Mock
    private GithubUserTokenService userTokenService;
    @Mock
    private GithubRepositoryRepository repositoryRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private GithubRequestBudget budget;

    private GithubInstallationService service;

    @BeforeEach
    void setUp() {
        given(userTokenService.require(USER_ID)).willReturn(TOKEN);
        given(apiClient.newOperationBudget()).willReturn(budget);
        given(repositoryRepository.findGithubRepositoryIdsByProjectId(anyLong()))
                .willReturn(List.of());
        service = new GithubInstallationService(
                apiClient, userTokenService, repositoryRepository, projectRepository, properties(),
                new GithubUserOperationLimiter(
                        new GithubOperationProperties(
                                50, Duration.ofSeconds(30), 1, Duration.ofSeconds(3))));
    }

    private static GithubAppProperties properties() {
        return new GithubAppProperties(
                "app-id", "galpi-app", "client-id", "client-secret", "private-key",
                "https://api.galpi.dev", "2022-11-28", "https://api.github.com",
                "https://github.com", "Galpi", List.of("https://galpi.dev"),
                "https://galpi.dev/auth/callback",
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2, 10);
    }

    private static GithubInstallationResponse installation(long id, String login, String type) {
        return new GithubInstallationResponse(
                id, new GithubInstallationResponse.Account(id * 10, login, type, "https://avatar"),
                "selected");
    }

    private static GithubInstallationResponse suspendedInstallation(
            long id, String login, String type) {
        return new GithubInstallationResponse(
                id, new GithubInstallationResponse.Account(id * 10, login, type, "https://avatar"),
                "selected", OffsetDateTime.now());
    }

    private static GithubRepositoryResponse repository(long id, String fullName, boolean isPrivate) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        String name = fullName.substring(fullName.indexOf('/') + 1);
        return new GithubRepositoryResponse(
                id, name, fullName,
                new GithubRepositoryResponse.Owner(1L, owner, "User"),
                isPrivate, "main", "https://github.com/" + fullName, Map.of("pull", true));
    }

    private static Project project() {
        return Project.create(mock(User.class), "갈피");
    }

    @Nested
    @DisplayName("저장소 목록")
    class ListRepositories {

        @Test
        @DisplayName("개인 계정과 조직을 installation 단위로 나눠 준다")
        void groupsByInstallation() {
            given(apiClient.getUserInstallations(TOKEN, budget)).willReturn(List.of(
                    installation(PERSONAL_INSTALLATION, "wb", "User"),
                    installation(ORG_INSTALLATION, "galpiii", "Organization")));
            given(apiClient.getInstallationRepositories(TOKEN, PERSONAL_INSTALLATION, budget))
                    .willReturn(List.of(repository(1L, "wb/notes", true)));
            given(apiClient.getInstallationRepositories(TOKEN, ORG_INSTALLATION, budget))
                    .willReturn(List.of(repository(2L, "galpiii/backend", true)));

            List<InstallationRepositoriesResponse> grouped = service.listRepositories(USER_ID, null);

            assertThat(grouped).hasSize(2);
            assertThat(grouped.getFirst().installation().accountType()).isEqualTo("User");
            assertThat(grouped.getLast().installation().accountType()).isEqualTo("Organization");
            assertThat(grouped.getLast().repositories()).singleElement()
                    .satisfies(repo -> assertThat(repo.fullName()).isEqualTo("galpiii/backend"));
        }

        @Test
        @DisplayName("조직 설치 설정 링크는 조직 경로로 만든다")
        void buildsOrgSettingsUrl() {
            given(apiClient.getUserInstallations(TOKEN, budget))
                    .willReturn(List.of(installation(ORG_INSTALLATION, "galpiii", "Organization")));
            given(apiClient.getInstallationRepositories(TOKEN, ORG_INSTALLATION, budget))
                    .willReturn(List.of());

            assertThat(service.listRepositories(USER_ID, null).getFirst().installation().settingsUrl())
                    .isEqualTo("https://github.com/organizations/galpiii/settings/installations/200");
        }

        @Test
        @DisplayName("비공개 저장소만 있는 계정도 그대로 돌려준다")
        void keepsPrivateOnlyAccount() {
            given(apiClient.getUserInstallations(TOKEN, budget))
                    .willReturn(List.of(installation(PERSONAL_INSTALLATION, "wb", "User")));
            given(apiClient.getInstallationRepositories(TOKEN, PERSONAL_INSTALLATION, budget))
                    .willReturn(List.of(repository(1L, "wb/secret", true)));

            assertThat(service.listRepositories(USER_ID, null).getFirst().repositories())
                    .singleElement()
                    .satisfies(repo -> assertThat(repo.isPrivate()).isTrue());
        }

        @Test
        @DisplayName("이미 연결된 저장소에 표시를 단다")
        void marksLinkedRepositories() {
            given(projectRepository.findByIdAndUserId(PROJECT_ID, USER_ID))
                    .willReturn(Optional.of(project()));
            given(repositoryRepository.findGithubRepositoryIdsByProjectId(PROJECT_ID))
                    .willReturn(List.of(1L));
            given(apiClient.getUserInstallations(TOKEN, budget))
                    .willReturn(List.of(installation(PERSONAL_INSTALLATION, "wb", "User")));
            given(apiClient.getInstallationRepositories(TOKEN, PERSONAL_INSTALLATION, budget))
                    .willReturn(List.of(repository(1L, "wb/notes", true), repository(2L, "wb/other", false)));

            List<com.github.galpiii.galpi.domain.github.dto.SelectableRepositoryResponse> repos =
                    service.listRepositories(USER_ID, PROJECT_ID).getFirst().repositories();

            assertThat(repos).extracting("githubRepositoryId", "linked")
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, true),
                            org.assertj.core.groups.Tuple.tuple(2L, false));
        }

        @Test
        @DisplayName("목록 GET은 연결된 저장소 스냅샷을 변경하지 않는다")
        void doesNotRefreshSnapshotsWhileListing() {
            given(projectRepository.findByIdAndUserId(PROJECT_ID, USER_ID))
                    .willReturn(Optional.of(project()));
            given(apiClient.getUserInstallations(TOKEN, budget))
                    .willReturn(List.of(installation(PERSONAL_INSTALLATION, "wb", "User")));
            given(apiClient.getInstallationRepositories(TOKEN, PERSONAL_INSTALLATION, budget))
                    .willReturn(List.of(repository(1L, "galpiii/journal", true)));

            service.listRepositories(USER_ID, PROJECT_ID);

            verify(repositoryRepository).findGithubRepositoryIdsByProjectId(PROJECT_ID);
            verify(repositoryRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("남의 프로젝트 id를 주면 거부한다")
        void rejectsForeignProject() {
            given(projectRepository.findByIdAndUserId(PROJECT_ID, USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.listRepositories(USER_ID, PROJECT_ID))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
        }

        @Test
        @DisplayName("user token이 없으면 재연결을 요구한다")
        void requiresReauthWithoutToken() {
            given(userTokenService.require(USER_ID)).willThrow(new GithubReauthRequiredException());

            assertThatThrownBy(() -> service.listRepositories(USER_ID, null))
                    .isInstanceOf(GithubReauthRequiredException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GITHUB_REAUTH_REQUIRED);
        }

        @Test
        @DisplayName("정지된 installation은 상태만 돌려주고 저장소 API는 호출하지 않는다")
        void keepsSuspendedInstallationWithoutFetchingRepositories() {
            given(apiClient.getUserInstallations(TOKEN, budget)).willReturn(List.of(
                    suspendedInstallation(PERSONAL_INSTALLATION, "wb", "User"),
                    installation(ORG_INSTALLATION, "galpiii", "Organization")));
            given(apiClient.getInstallationRepositories(TOKEN, ORG_INSTALLATION, budget))
                    .willReturn(List.of(repository(2L, "galpiii/backend", true)));

            List<InstallationRepositoriesResponse> grouped =
                    service.listRepositories(USER_ID, null);

            assertThat(grouped).hasSize(2);
            assertThat(grouped.getFirst().installation().suspended()).isTrue();
            assertThat(grouped.getFirst().repositories()).isEmpty();
            verify(apiClient, never()).getInstallationRepositories(
                    TOKEN, PERSONAL_INSTALLATION, budget);
        }

        @Test
        @DisplayName("목록과 상세 조회 사이에 삭제된 installation만 건너뛴다")
        void skipsInstallationRemovedDuringListing() {
            given(apiClient.getUserInstallations(TOKEN, budget)).willReturn(List.of(
                    installation(PERSONAL_INSTALLATION, "wb", "User"),
                    installation(ORG_INSTALLATION, "galpiii", "Organization")));
            given(apiClient.getInstallationRepositories(TOKEN, PERSONAL_INSTALLATION, budget))
                    .willThrow(new GithubInstallationUnavailableException());
            given(apiClient.getInstallationRepositories(TOKEN, ORG_INSTALLATION, budget))
                    .willReturn(List.of(repository(2L, "galpiii/backend", true)));

            assertThat(service.listRepositories(USER_ID, null))
                    .singleElement()
                    .satisfies(group -> assertThat(group.installation().installationId())
                            .isEqualTo(ORG_INSTALLATION));
        }

        @Test
        @DisplayName("화면용 요청 budget이 소진되면 처리한 installation까지만 반환한다")
        void stopsWithPartialResultWhenRequestBudgetIsExhausted() {
            given(apiClient.getUserInstallations(TOKEN, budget)).willReturn(List.of(
                    installation(PERSONAL_INSTALLATION, "wb", "User"),
                    installation(ORG_INSTALLATION, "galpiii", "Organization")));
            given(budget.isRequestLimitReached()).willReturn(false, true);
            given(apiClient.getInstallationRepositories(TOKEN, PERSONAL_INSTALLATION, budget))
                    .willReturn(List.of(repository(1L, "wb/notes", true)));

            assertThat(service.listRepositories(USER_ID, null))
                    .singleElement()
                    .satisfies(group -> assertThat(group.installation().installationId())
                            .isEqualTo(PERSONAL_INSTALLATION));
            verify(apiClient, never()).getInstallationRepositories(
                    TOKEN, ORG_INSTALLATION, budget);
        }
    }

    @Nested
    @DisplayName("접근 가능한 저장소 대조")
    class AccessibleSnapshots {

        @Test
        @DisplayName("여러 installation의 저장소를 하나의 맵으로 모은다")
        void collectsAcrossInstallations() {
            given(apiClient.getUserInstallationsComplete(TOKEN, budget)).willReturn(List.of(
                    installation(PERSONAL_INSTALLATION, "wb", "User"),
                    installation(ORG_INSTALLATION, "galpiii", "Organization")));
            given(apiClient.getInstallationRepositoriesComplete(
                    TOKEN, PERSONAL_INSTALLATION, budget))
                    .willReturn(List.of(repository(1L, "wb/notes", true)));
            given(apiClient.getInstallationRepositoriesComplete(TOKEN, ORG_INSTALLATION, budget))
                    .willReturn(List.of(repository(2L, "galpiii/backend", true)));

            Map<Long, RepositorySnapshot> snapshots =
                    service.accessibleSnapshots(USER_ID, Set.of(1L, 2L));

            assertThat(snapshots).containsOnlyKeys(1L, 2L);
            assertThat(snapshots.get(1L).installationId()).isEqualTo(PERSONAL_INSTALLATION);
            assertThat(snapshots.get(2L).installationId()).isEqualTo(ORG_INSTALLATION);
        }

        @Test
        @DisplayName("요청한 저장소를 다 찾으면 남은 installation은 조회하지 않는다")
        void stopsOnceEveryRequestedRepositoryIsFound() {
            given(apiClient.getUserInstallationsComplete(TOKEN, budget)).willReturn(List.of(
                    installation(PERSONAL_INSTALLATION, "wb", "User"),
                    installation(ORG_INSTALLATION, "galpiii", "Organization")));
            given(apiClient.getInstallationRepositoriesComplete(
                    TOKEN, PERSONAL_INSTALLATION, budget))
                    .willReturn(List.of(repository(1L, "wb/notes", true)));

            assertThat(service.accessibleSnapshots(USER_ID, Set.of(1L))).containsOnlyKeys(1L);

            verify(apiClient, never()).getInstallationRepositoriesComplete(
                    TOKEN, ORG_INSTALLATION, budget);
        }

        @Test
        @DisplayName("요청하지 않은 저장소는 담지 않는다 — 전체 목록을 메모리에 올리지 않는다")
        void keepsOnlyRequestedRepositories() {
            given(apiClient.getUserInstallationsComplete(TOKEN, budget))
                    .willReturn(List.of(installation(PERSONAL_INSTALLATION, "wb", "User")));
            given(apiClient.getInstallationRepositoriesComplete(
                    TOKEN, PERSONAL_INSTALLATION, budget))
                    .willReturn(List.of(repository(1L, "wb/notes", true),
                            repository(2L, "wb/other", false),
                            repository(3L, "wb/third", false)));

            assertThat(service.accessibleSnapshots(USER_ID, Set.of(2L))).containsOnlyKeys(2L);
        }

        @Test
        @DisplayName("빈 요청은 GitHub을 부르지 않는다")
        void skipsGithubForEmptyRequest() {
            assertThat(service.accessibleSnapshots(USER_ID, Set.of())).isEmpty();

            verify(apiClient, never()).getUserInstallationsComplete(TOKEN, budget);
        }

        @Test
        @DisplayName("잘린 목록으로는 권한을 판정하지 않는다 — 부분 결과를 허용하지 않는 경로를 쓴다")
        void refusesTruncatedList() {
            given(apiClient.getUserInstallationsComplete(TOKEN, budget))
                    .willThrow(new GithubApiException(ErrorCode.GITHUB_REPOSITORY_LIST_INCOMPLETE));

            assertThatThrownBy(() -> service.accessibleSnapshots(USER_ID, Set.of(1L)))
                    .isInstanceOf(GithubApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.GITHUB_REPOSITORY_LIST_INCOMPLETE);

            verify(apiClient, never()).getUserInstallations(TOKEN, budget);
        }

        @Test
        @DisplayName("권한 대조 중 사라진 installation 뒤의 정상 저장소를 계속 찾는다")
        void continuesAfterUnavailableInstallation() {
            given(apiClient.getUserInstallationsComplete(TOKEN, budget)).willReturn(List.of(
                    installation(PERSONAL_INSTALLATION, "wb", "User"),
                    installation(ORG_INSTALLATION, "galpiii", "Organization")));
            given(apiClient.getInstallationRepositoriesComplete(
                    TOKEN, PERSONAL_INSTALLATION, budget))
                    .willThrow(new GithubInstallationUnavailableException());
            given(apiClient.getInstallationRepositoriesComplete(TOKEN, ORG_INSTALLATION, budget))
                    .willReturn(List.of(repository(2L, "galpiii/backend", true)));

            assertThat(service.accessibleSnapshots(USER_ID, Set.of(2L))).containsOnlyKeys(2L);
        }

        @Test
        @DisplayName("누락된 installation 때문에 권한 대조가 불완전하면 403 대신 재시도 오류를 낸다")
        void failsWhenUnavailableInstallationLeavesRequestedRepositoryUnresolved() {
            given(apiClient.getUserInstallationsComplete(TOKEN, budget)).willReturn(List.of(
                    installation(PERSONAL_INSTALLATION, "wb", "User")));
            given(apiClient.getInstallationRepositoriesComplete(
                    TOKEN, PERSONAL_INSTALLATION, budget))
                    .willThrow(new GithubInstallationUnavailableException());

            assertThatThrownBy(() -> service.accessibleSnapshots(USER_ID, Set.of(1L)))
                    .isInstanceOf(GithubInstallationUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("installation_id 대조")
    class OwnsInstallation {

        @Test
        @DisplayName("사용자 설치 목록에 있으면 통과한다")
        void acceptsOwnInstallation() {
            given(apiClient.getUserInstallationsComplete(TOKEN, budget))
                    .willReturn(List.of(installation(PERSONAL_INSTALLATION, "wb", "User")));

            assertThat(service.ownsInstallation(USER_ID, PERSONAL_INSTALLATION)).isTrue();
            verify(apiClient, times(1)).getUserInstallationsComplete(TOKEN, budget);
        }

        @Test
        @DisplayName("목록에 없으면 servlet thread를 붙잡고 재시도하지 않고 거부한다")
        void rejectsForeignInstallationWithoutBlockingRetry() {
            given(apiClient.getUserInstallationsComplete(TOKEN, budget))
                    .willReturn(List.of(installation(PERSONAL_INSTALLATION, "wb", "User")));

            assertThat(service.ownsInstallation(USER_ID, 999_999L)).isFalse();
            verify(apiClient, times(1)).getUserInstallationsComplete(TOKEN, budget);
        }
    }
}

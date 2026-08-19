package com.github.galpiii.galpi.domain.analysis.service;

import com.github.galpiii.galpi.domain.analysis.dto.AnalysisRunCreatedResponse;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunTargetRepository;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.ForbiddenException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AnalysisRunService — 작업 생성 시 권한 재검증")
class AnalysisRunServiceTest {

    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 3L;
    private static final long PERSONAL_INSTALLATION = 100L;
    private static final long ORG_INSTALLATION = 200L;

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private GithubRepositoryRepository repositoryRepository;
    @Mock
    private GithubInstallationService installationService;
    @Mock
    private AnalysisRunRepository runRepository;
    @Mock
    private AnalysisRunTargetRepository targetRepository;
    @Mock
    private AnalysisRunCreator creator;

    @InjectMocks
    private AnalysisRunService service;

    private Project project;

    @BeforeEach
    void setUp() {
        User user = User.ofGithub(999L, "wb", "wb", null, "https://avatar");
        project = Project.create(user, "갈피");
        given(projectRepository.findByIdAndUserId(PROJECT_ID, USER_ID))
                .willReturn(Optional.of(project));
        given(runRepository.existsByProjectIdAndStatusIn(anyLong(), anyList())).willReturn(false);
        given(creator.create(anyLong(), anyLong(), anyList(), any())).willReturn(55L);
    }

    @Nested
    @DisplayName("권한 재검증")
    class AccessRevalidation {

        @Test
        @DisplayName("접근 가능한 저장소로 작업을 만들고 installation 매핑을 고정한다")
        void createsRunWithInstallationSnapshot() {
            GithubRepository personal = repository(1L, 11L, "wb/personal", PERSONAL_INSTALLATION);
            GithubRepository organization = repository(2L, 22L, "acme/api", ORG_INSTALLATION);
            given(repositoryRepository.findAllByProjectId(PROJECT_ID))
                    .willReturn(List.of(personal, organization));
            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(accessible(
                            snapshot(11L, "wb/personal", PERSONAL_INSTALLATION),
                            snapshot(22L, "acme/api", ORG_INSTALLATION)));

            AnalysisRunCreatedResponse response = service.create(USER_ID, PROJECT_ID);

            assertThat(response.analysisRunId()).isEqualTo(55L);
            assertThat(response.status()).isEqualTo(AnalysisRunStatus.QUEUED);
            assertThat(response.repositoryCount()).isEqualTo(2);
            assertThat(response.inaccessibleRepositoryCount()).isZero();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<Long, Long>> snapshotCaptor = ArgumentCaptor.forClass(Map.class);
            verify(creator).create(eq(USER_ID), eq(PROJECT_ID), anyList(),
                    snapshotCaptor.capture());
            // 서로 다른 installation의 저장소가 한 프로젝트에 섞이는 것이 기본 사용 방식이다.
            assertThat(snapshotCaptor.getValue())
                    .containsEntry(11L, PERSONAL_INSTALLATION)
                    .containsEntry(22L, ORG_INSTALLATION);
        }

        @Test
        @DisplayName("접근 권한을 잃은 저장소는 INACCESSIBLE로 표시하고 분석에서 뺀다")
        void marksInaccessibleRepositories() {
            GithubRepository accessible = repository(1L, 11L, "wb/personal", PERSONAL_INSTALLATION);
            GithubRepository revoked = repository(2L, 22L, "acme/api", ORG_INSTALLATION);
            given(repositoryRepository.findAllByProjectId(PROJECT_ID))
                    .willReturn(List.of(accessible, revoked));
            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(accessible(snapshot(11L, "wb/personal", PERSONAL_INSTALLATION)));

            AnalysisRunCreatedResponse response = service.create(USER_ID, PROJECT_ID);

            assertThat(response.repositoryCount()).isEqualTo(1);
            assertThat(response.inaccessibleRepositoryCount()).isEqualTo(1);
            verify(creator).markInaccessible(List.of(2L));
        }

        @Test
        @DisplayName("조직에서 제거돼 모든 저장소를 잃으면 분석을 시작할 수 없다")
        void rejectsWhenNoRepositoryIsAccessible() {
            GithubRepository revoked = repository(2L, 22L, "acme/api", ORG_INSTALLATION);
            given(repositoryRepository.findAllByProjectId(PROJECT_ID)).willReturn(List.of(revoked));
            given(installationService.accessibleSnapshots(eq(USER_ID), any()))
                    .willReturn(Map.of());

            assertThatThrownBy(() -> service.create(USER_ID, PROJECT_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.ANALYSIS_NO_ACCESSIBLE_REPOSITORY);

            verify(creator).markInaccessible(List.of(2L));
            verify(creator, never()).create(anyLong(), anyLong(), anyList(), any());
        }
    }

    @Nested
    @DisplayName("생성 거부")
    class Rejections {

        @Test
        @DisplayName("남의 프로젝트면 GitHub을 부르지 않고 거부한다")
        void rejectsOtherUsersProject() {
            given(projectRepository.findByIdAndUserId(PROJECT_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(USER_ID, PROJECT_ID))
                    .isInstanceOf(NotFoundException.class);

            verify(installationService, never()).accessibleSnapshots(anyLong(), any());
        }

        @Test
        @DisplayName("연결된 저장소가 없으면 거부한다")
        void rejectsProjectWithoutRepositories() {
            given(repositoryRepository.findAllByProjectId(PROJECT_ID)).willReturn(List.of());

            assertThatThrownBy(() -> service.create(USER_ID, PROJECT_ID))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANALYSIS_NO_REPOSITORY);
        }

        @Test
        @DisplayName("이미 진행 중인 분석이 있으면 거부한다")
        void rejectsWhenAnotherRunIsInFlight() {
            given(runRepository.existsByProjectIdAndStatusIn(eq(PROJECT_ID), anyList()))
                    .willReturn(true);

            assertThatThrownBy(() -> service.create(USER_ID, PROJECT_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANALYSIS_ALREADY_RUNNING);

            verify(installationService, never()).accessibleSnapshots(anyLong(), any());
        }
    }

    private GithubRepository repository(long id, long githubRepositoryId, String fullName,
                                        long installationId) {
        GithubRepository repository = GithubRepository.link(project,
                snapshot(githubRepositoryId, fullName, installationId));
        setId(repository, id);
        return repository;
    }

    /** 저장 전 엔티티라 id가 없다. 서비스가 id로 대상을 지정하므로 채워 넣는다. */
    private static void setId(Object entity, long id) {
        try {
            var field = entity.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RepositorySnapshot snapshot(long githubRepositoryId, String fullName,
                                               long installationId) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        String name = fullName.substring(fullName.indexOf('/') + 1);
        return new RepositorySnapshot(githubRepositoryId, installationId, owner, name, fullName,
                true, "main", "https://github.com/" + fullName);
    }

    private static Map<Long, RepositorySnapshot> accessible(RepositorySnapshot... snapshots) {
        Map<Long, RepositorySnapshot> map = new LinkedHashMap<>();
        for (RepositorySnapshot snapshot : snapshots) {
            map.put(snapshot.githubRepositoryId(), snapshot);
        }
        return map;
    }
}

package com.github.galpiii.galpi.domain.project.service;

import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.config.ProjectProperties;
import com.github.galpiii.galpi.domain.project.dto.ProjectCreateRequest;
import com.github.galpiii.galpi.domain.project.dto.ProjectCreatedResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectSort;
import com.github.galpiii.galpi.domain.project.dto.ProjectUpdateRequest;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.entity.ProjectOnboardingStep;
import com.github.galpiii.galpi.domain.project.entity.ProjectStatus;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProjectService — 프로젝트 CRUD")
class ProjectServiceTest {

    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 3L;

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private GithubRepositoryRepository repositoryRepository;
    @Mock
    private SpecDocumentRepository specDocumentRepository;
    @Mock
    private AnalysisRunRepository analysisRunRepository;

    private ProjectService service;
    private User owner;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, userRepository, repositoryRepository,
                specDocumentRepository, analysisRunRepository,
                new ProjectProperties(50, 100, 20));
        owner = mock(User.class);

        given(userRepository.findById(USER_ID)).willReturn(Optional.of(owner));
        given(repositoryRepository.findAllByProjectId(anyLong())).willReturn(List.of());
        given(projectRepository.save(any()))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(projectRepository.findSummaries(anyLong(), any(), any()))
                .willReturn(Page.empty());
    }

    private void givenOwnedProject(Project project) {
        given(projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(PROJECT_ID, USER_ID))
                .willReturn(Optional.of(project));
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("이름만으로 만들어지고 DRAFT · SPEC에서 시작한다")
        void createsDraft() {
            ProjectCreatedResponse response =
                    service.create(USER_ID, new ProjectCreateRequest("GDG 통합 플랫폼"));

            assertThat(response.name()).isEqualTo("GDG 통합 플랫폼");
            assertThat(response.status()).isEqualTo(ProjectStatus.DRAFT);
            assertThat(response.onboardingStep()).isEqualTo(ProjectOnboardingStep.SPEC);
        }

        @Test
        @DisplayName("이름 앞뒤 공백은 검증 전에 다듬어진다")
        void trimsName() {
            assertThat(new ProjectCreateRequest("  갈피  ").name()).isEqualTo("갈피");
        }

        @Test
        @DisplayName("상한에 도달하면 만들지 않는다")
        void rejectsBeyondLimit() {
            given(projectRepository.countByOwnerIdAndDeletedAtIsNull(USER_ID)).willReturn(50L);

            assertThatThrownBy(() -> service.create(USER_ID, new ProjectCreateRequest("갈피")))
                    .isInstanceOf(ConflictException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_LIMIT_EXCEEDED);

            verify(projectRepository, never()).save(any());
        }

        @Test
        @DisplayName("삭제한 프로젝트는 상한에 세지 않는다")
        void countsOnlyLiveProjects() {
            service.create(USER_ID, new ProjectCreateRequest("갈피"));

            verify(projectRepository).countByOwnerIdAndDeletedAtIsNull(USER_ID);
        }
    }

    @Nested
    @DisplayName("목록")
    class ListProjects {

        @Test
        @DisplayName("status를 주지 않으면 ARCHIVED를 뺀 목록을 최신 수정순으로 준다")
        void excludesArchivedByDefault() {
            service.list(USER_ID, null, null, null, null);

            ArgumentCaptor<Collection<ProjectStatus>> statuses = ArgumentCaptor.captor();
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.captor();
            verify(projectRepository).findSummaries(eq(USER_ID), statuses.capture(),
                    pageable.capture());

            assertThat(statuses.getValue())
                    .containsExactlyInAnyOrder(ProjectStatus.DRAFT, ProjectStatus.ACTIVE);
            assertThat(pageable.getValue().getSort().getOrderFor("updatedAt"))
                    .isNotNull()
                    .extracting(Sort.Order::getDirection)
                    .isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("status를 주면 그 상태만 본다")
        void filtersByStatus() {
            service.list(USER_ID, ProjectStatus.ARCHIVED, ProjectSort.NAME, 1, 5);

            ArgumentCaptor<Collection<ProjectStatus>> statuses = ArgumentCaptor.captor();
            verify(projectRepository).findSummaries(eq(USER_ID), statuses.capture(),
                    any(PageRequest.class));

            assertThat(statuses.getValue()).containsExactly(ProjectStatus.ARCHIVED);
        }

        @Test
        @DisplayName("클라이언트가 보낸 페이지 크기는 상한에서 잘린다")
        void capsPageSize() {
            service.list(USER_ID, null, null, 0, 5_000);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.captor();
            verify(projectRepository).findSummaries(eq(USER_ID), any(), pageable.capture());

            assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("소유권")
    class Ownership {

        @Test
        @DisplayName("남의 프로젝트나 삭제된 프로젝트는 404다")
        void hidesForeignProject() {
            given(projectRepository.findByIdAndOwnerIdAndDeletedAtIsNull(PROJECT_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(USER_ID, PROJECT_ID))
                    .isInstanceOf(NotFoundException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
            assertThatThrownBy(() -> service.delete(USER_ID, PROJECT_ID))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.update(USER_ID, PROJECT_ID,
                    new ProjectUpdateRequest("새 이름", null, null)))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("수정")
    class Update {

        @Test
        @DisplayName("이름을 바꾼다")
        void rename() {
            Project project = Project.create(owner, "갈피");
            givenOwnedProject(project);

            service.update(USER_ID, PROJECT_ID, new ProjectUpdateRequest("갈피 v2", null, null));

            assertThat(project.getName()).isEqualTo("갈피 v2");
        }

        @Test
        @DisplayName("바꿀 값이 하나도 없으면 거부한다")
        void rejectsEmptyRequest() {
            givenOwnedProject(Project.create(owner, "갈피"));

            assertThatThrownBy(() -> service.update(USER_ID, PROJECT_ID,
                    new ProjectUpdateRequest(null, null, null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_UPDATE_EMPTY);
        }

        @Test
        @DisplayName("ACTIVE와 ARCHIVED 사이는 오갈 수 있다")
        void archivesAndRestores() {
            Project project = Project.create(owner, "갈피");
            project.markRepositoriesLinked();
            givenOwnedProject(project);

            service.update(USER_ID, PROJECT_ID,
                    new ProjectUpdateRequest(null, ProjectStatus.ARCHIVED, null));
            assertThat(project.getStatus()).isEqualTo(ProjectStatus.ARCHIVED);

            service.update(USER_ID, PROJECT_ID,
                    new ProjectUpdateRequest(null, ProjectStatus.ACTIVE, null));
            assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        }

        @Test
        @DisplayName("DRAFT로 되돌리는 전이는 거부한다")
        void rejectsBackToDraft() {
            Project project = Project.create(owner, "갈피");
            project.markRepositoriesLinked();
            givenOwnedProject(project);

            assertThatThrownBy(() -> service.update(USER_ID, PROJECT_ID,
                    new ProjectUpdateRequest(null, ProjectStatus.DRAFT, null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.PROJECT_STATUS_TRANSITION_NOT_ALLOWED);

            assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        }

        @Test
        @DisplayName("저장소도 없는 DRAFT를 보관 상태로 넘기지 않는다")
        void rejectsArchivingDraft() {
            givenOwnedProject(Project.create(owner, "갈피"));

            assertThatThrownBy(() -> service.update(USER_ID, PROJECT_ID,
                    new ProjectUpdateRequest(null, ProjectStatus.ARCHIVED, null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ErrorCode.PROJECT_STATUS_TRANSITION_NOT_ALLOWED);
        }

        @Test
        @DisplayName("온보딩 단계는 앞으로만 간다")
        void advancesOnboardingStepForwardOnly() {
            Project project = Project.create(owner, "갈피");
            givenOwnedProject(project);

            service.update(USER_ID, PROJECT_ID, new ProjectUpdateRequest(
                    null, null, ProjectOnboardingStep.REPOSITORIES));
            assertThat(project.getOnboardingStep())
                    .isEqualTo(ProjectOnboardingStep.REPOSITORIES);

            service.update(USER_ID, PROJECT_ID, new ProjectUpdateRequest(
                    null, null, ProjectOnboardingStep.SPEC));
            assertThat(project.getOnboardingStep())
                    .isEqualTo(ProjectOnboardingStep.REPOSITORIES);
        }
    }

    @Nested
    @DisplayName("삭제")
    class Delete {

        @Test
        @DisplayName("행을 지우지 않고 deleted_at만 채운다")
        void softDeletes() {
            Project project = Project.create(owner, "갈피");
            givenOwnedProject(project);

            service.delete(USER_ID, PROJECT_ID);

            assertThat(project.isDeleted()).isTrue();
            verify(projectRepository, never()).delete(any());
        }

        @Test
        @DisplayName("진행 중이던 분석을 함께 취소한다")
        void cancelsInFlightRuns() {
            givenOwnedProject(Project.create(owner, "갈피"));

            service.delete(USER_ID, PROJECT_ID);

            verify(analysisRunRepository).cancelInFlight(eq(PROJECT_ID), any());
        }
    }
}

package com.github.galpiii.galpi.domain.project.service;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunRepository;
import com.github.galpiii.galpi.domain.analysis.service.AnalysisRunService;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationService;
import com.github.galpiii.galpi.domain.project.dto.ProjectCreateRequest;
import com.github.galpiii.galpi.domain.project.dto.ProjectDetailResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectListResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectSummaryResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectUpdateRequest;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.entity.ProjectOnboardingStep;
import com.github.galpiii.galpi.domain.project.entity.ProjectStatus;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 프로젝트 생명주기를 실제 Postgres에 붙여 확인한다.
 *
 * <p>mock으로는 확인할 수 없는 것이 세 가지 있다. 목록 쿼리는 저장소 개수 집계와
 * {@code last_analysis_run_id} 조인을 한 문장에 담고 있어 실제로 실행해 봐야 하고,
 * 삭제 시 진행 중 작업 취소는 벌크 갱신이라 트랜잭션 경계가 필요하며, V7 마이그레이션과
 * 엔티티 매핑의 대조는 {@code ddl-auto=validate}가 붙어야 일어난다.
 */
@DisplayName("프로젝트 생명주기 — 실제 Postgres")
class ProjectLifecycleIntegrationTest extends IntegrationTestSupport {

    private static final long PERSONAL_INSTALLATION = 100L;

    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectRepositoryLinkWriter linkWriter;
    @Autowired
    private ProjectRepositoryService projectRepositoryService;
    @Autowired
    private AnalysisRunService analysisRunService;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private GithubRepositoryRepository repositoryRepository;
    @Autowired
    private AnalysisRunRepository runRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private GithubInstallationService installationService;

    private Long userId;

    @BeforeEach
    void setUp() {
        runRepository.deleteAll();
        repositoryRepository.deleteAll();
        projectRepository.deleteAll();

        userId = userRepository.save(
                User.ofGithub(System.nanoTime(), "wb", "wb", null, "https://avatar")).getId();
    }

    private Long createProject(String name) {
        return projectService.create(userId, new ProjectCreateRequest(name)).id();
    }

    private static RepositorySnapshot snapshot(long id, String fullName) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        String name = fullName.substring(fullName.indexOf('/') + 1);
        return new RepositorySnapshot(id, PERSONAL_INSTALLATION, owner, name, fullName, true,
                "main", "https://github.com/" + fullName);
    }

    private Long queueRun(Long projectId) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        AnalysisRun run = runRepository.save(AnalysisRun.queue(
                project, project.getOwner(), Map.of(1L, PERSONAL_INSTALLATION)));
        project.markLastAnalysisRun(run.getId());
        projectRepository.save(project);
        return run.getId();
    }

    @Test
    @DisplayName("만든 직후에는 DRAFT로 목록에 남는다")
    void createdProjectStaysAsDraft() {
        Long projectId = createProject("갈피");

        ProjectListResponse list = projectService.list(userId, null, null, null, null);

        assertThat(list.projects()).singleElement().satisfies(project -> {
            assertThat(project.id()).isEqualTo(projectId);
            assertThat(project.status()).isEqualTo(ProjectStatus.DRAFT);
            assertThat(project.onboardingStep()).isEqualTo(ProjectOnboardingStep.SPEC);
            assertThat(project.repositoryCount()).isZero();
            assertThat(project.hasSpecDocument()).isFalse();
            assertThat(project.lastAnalysis()).isNull();
        });
    }

    @Test
    @DisplayName("저장소를 연결하면 ACTIVE가 되고 위저드가 분석 단계로 넘어간다")
    void activatesWhenRepositoriesLinked() {
        Long projectId = createProject("갈피");

        linkWriter.link(userId, projectId, List.of(1L),
                Map.of(1L, snapshot(1L, "wb/notes")));

        Project project = projectRepository.findById(projectId).orElseThrow();
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(project.getOnboardingStep()).isEqualTo(ProjectOnboardingStep.ANALYSIS);
    }

    @Test
    @DisplayName("마지막 저장소를 빼도 DRAFT로 되돌아가지 않는다")
    void doesNotFallBackToDraft() {
        Long projectId = createProject("갈피");
        linkWriter.link(userId, projectId, List.of(1L), Map.of(1L, snapshot(1L, "wb/notes")));

        Long repositoryId = repositoryRepository.findAllByProjectId(projectId).getFirst().getId();
        projectRepositoryService.unlink(userId, projectId, repositoryId);

        assertThat(projectRepository.findById(projectId).orElseThrow().getStatus())
                .isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    @DisplayName("목록이 저장소 개수와 최근 분석 상태를 한 번에 채운다")
    void listCarriesRepositoryCountAndLastAnalysis() {
        Long projectId = createProject("갈피");
        linkWriter.link(userId, projectId, List.of(1L, 2L),
                Map.of(1L, snapshot(1L, "wb/notes"), 2L, snapshot(2L, "wb/app")));
        Long runId = queueRun(projectId);

        ProjectSummaryResponse summary =
                projectService.list(userId, null, null, null, null).projects().getFirst();

        assertThat(summary.repositoryCount()).isEqualTo(2);
        assertThat(summary.lastAnalysis()).isNotNull();
        assertThat(summary.lastAnalysis().analysisRunId()).isEqualTo(runId);
        assertThat(summary.lastAnalysis().status()).isEqualTo(AnalysisRunStatus.QUEUED);
    }

    @Test
    @DisplayName("보관한 프로젝트는 기본 목록에서 빠지고 status로 찾으면 나온다")
    void archivedProjectsAreHiddenByDefault() {
        Long projectId = createProject("갈피");
        linkWriter.link(userId, projectId, List.of(1L), Map.of(1L, snapshot(1L, "wb/notes")));
        projectService.update(userId, projectId,
                new ProjectUpdateRequest(null, ProjectStatus.ARCHIVED, null));

        assertThat(projectService.list(userId, null, null, null, null).projects()).isEmpty();
        assertThat(projectService.list(userId, ProjectStatus.ARCHIVED, null, null, null)
                .projects()).hasSize(1);
    }

    @Test
    @DisplayName("삭제하면 진행 중이던 분석이 취소되고 하위 리소스가 전부 404가 된다")
    void deleteCancelsRunsAndHidesChildResources() {
        Long projectId = createProject("갈피");
        linkWriter.link(userId, projectId, List.of(1L), Map.of(1L, snapshot(1L, "wb/notes")));
        Long runId = queueRun(projectId);

        projectService.delete(userId, projectId);

        assertThat(runRepository.findById(runId).orElseThrow().getStatus())
                .isEqualTo(AnalysisRunStatus.CANCELLED);
        assertThat(projectRepository.findById(projectId).orElseThrow().isDeleted()).isTrue();
        assertThat(projectService.list(userId, null, null, null, null).projects()).isEmpty();

        assertThatThrownBy(() -> projectService.get(userId, projectId))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
        assertThatThrownBy(() -> projectRepositoryService.list(userId, projectId))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
        assertThatThrownBy(() -> analysisRunService.get(userId, runId))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANALYSIS_RUN_NOT_FOUND);
    }

    @Test
    @DisplayName("취소된 작업은 워커가 계속 진행하지 않는다")
    void cancelledRunIsAbandoned() {
        Long projectId = createProject("갈피");
        Long runId = queueRun(projectId);

        projectService.delete(userId, projectId);

        assertThat(runRepository.isAbandoned(runId)).isTrue();
    }

    @Test
    @DisplayName("이미 끝난 분석은 삭제해도 상태가 덮이지 않는다")
    void keepsFinishedRunStatus() {
        Long projectId = createProject("갈피");
        Long runId = queueRun(projectId);
        AnalysisRun run = runRepository.findById(runId).orElseThrow();
        run.finish(AnalysisRunStatus.COMPLETED);
        runRepository.save(run);

        projectService.delete(userId, projectId);

        assertThat(runRepository.findById(runId).orElseThrow().getStatus())
                .isEqualTo(AnalysisRunStatus.COMPLETED);
    }

    @Test
    @DisplayName("남의 프로젝트는 목록에도 상세에도 나오지 않는다")
    void hidesOtherUsersProjects() {
        Long projectId = createProject("갈피");
        Long otherUserId = userRepository.save(
                User.ofGithub(System.nanoTime(), "other", "other", null, "https://avatar"))
                .getId();

        assertThat(projectService.list(otherUserId, null, null, null, null).projects()).isEmpty();
        assertThatThrownBy(() -> projectService.get(otherUserId, projectId))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("수정 응답의 updatedAt이 실제로 갱신된 값이다")
    void updateResponseCarriesFreshUpdatedAt() {
        Long projectId = createProject("갈피");
        OffsetDateTime before =
                projectRepository.findById(projectId).orElseThrow().getUpdatedAt();

        ProjectDetailResponse response = projectService.update(userId, projectId,
                new ProjectUpdateRequest("갈피 v2", null, null));

        // flush 전에 응답을 만들면 여기 수정 전 시각이 담긴다. 목록 정렬이 updatedAt 기준이라
        // 프론트가 그 값을 그대로 쓰면 방금 고친 프로젝트가 뒤로 밀린다.
        assertThat(response.updatedAt()).isAfter(before);
        assertThat(response.updatedAt())
                .isEqualTo(projectRepository.findById(projectId).orElseThrow().getUpdatedAt());
        assertThat(response.name()).isEqualTo("갈피 v2");
    }
}

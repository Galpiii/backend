package com.github.galpiii.galpi.domain.project.service;

import com.github.galpiii.galpi.domain.collection.entity.ChangeStatus;
import com.github.galpiii.galpi.domain.collection.pr.PullRequestWriter;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.dto.ProjectListResponse;
import com.github.galpiii.galpi.domain.project.dto.ProjectSummaryResponse;
import com.github.galpiii.galpi.domain.project.entity.Project;
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

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 연결 해제가 이력을 지우지 않는지 실제 Postgres에서 확인한다.
 *
 * <p>이 클래스가 따로 있는 이유는 {@code ON DELETE CASCADE}다. {@code pull_requests}와
 * {@code analysis_run_repositories}가 {@code repositories}를 CASCADE로 물고 있어서, 연결
 * 해제가 물리 삭제로 돌아가는 순간 저장소 하나를 빼는 것만으로 그 저장소의 수집 근거가
 * 전부 사라진다. 그 회귀는 mock으로는 절대 보이지 않는다 — CASCADE는 DB가 하는 일이다.
 */
@DisplayName("저장소 연결 해제 — 실제 Postgres")
class ProjectRepositoryUnlinkIntegrationTest extends IntegrationTestSupport {

    private static final long INSTALLATION_ID = 100L;
    private static final long GITHUB_REPOSITORY_ID = 11L;

    @Autowired
    private ProjectRepositoryService service;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectRepositoryLinkWriter linkWriter;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private GithubRepositoryRepository repositoryRepository;
    @Autowired
    private PullRequestRepository pullRequestRepository;
    @Autowired
    private PullRequestWriter pullRequestWriter;
    @Autowired
    private UserRepository userRepository;

    private Long userId;
    private Long projectId;
    private Long repositoryId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(
                User.ofGithub(System.nanoTime(), "wb", "wb", null, "https://avatar"));
        Project project = projectRepository.save(Project.create(user, "갈피"));
        userId = user.getId();
        projectId = project.getId();
        repositoryId = linkWriter
                .link(userId, projectId, List.of(GITHUB_REPOSITORY_ID), accessible())
                .getFirst()
                .getId();
    }

    @Test
    @DisplayName("연결을 끊어도 수집해 둔 PR이 남는다 — 물리 삭제였다면 CASCADE로 사라졌다")
    void keepsCollectedPullRequests() {
        pullRequestWriter.save(repositoryId, pullRequest(1));
        pullRequestWriter.save(repositoryId, pullRequest(2));

        service.unlink(userId, projectId, repositoryId);

        assertThat(pullRequestRepository.findAll()).hasSize(2);
        assertThat(repositoryRepository.findById(repositoryId)).isPresent();
    }

    @Test
    @DisplayName("끊은 저장소는 연결 목록에서 빠진다")
    void disappearsFromTheLinkedList() {
        service.unlink(userId, projectId, repositoryId);

        assertThat(service.list(userId, projectId)).isEmpty();
        assertThat(repositoryRepository.findAllByProjectId(projectId)).isEmpty();
    }

    @Test
    @DisplayName("끊은 저장소는 프로젝트 목록의 저장소 개수에서도 빠진다")
    void disappearsFromTheProjectSummaryCount() {
        service.unlink(userId, projectId, repositoryId);

        ProjectListResponse projects = projectService.list(userId, null, null, null, null);

        assertThat(projects.projects()).singleElement()
                .extracting(ProjectSummaryResponse::repositoryCount)
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("다시 연결하면 같은 행이 되살아나 PR 이력이 그대로 이어진다")
    void relinkRevivesTheSameRow() {
        pullRequestWriter.save(repositoryId, pullRequest(1));
        service.unlink(userId, projectId, repositoryId);

        List<GithubRepository> relinked =
                linkWriter.link(userId, projectId, List.of(GITHUB_REPOSITORY_ID), accessible());

        // 새 행을 만들지 않으므로 UNIQUE 제약에 걸리지도, 이력이 갈라지지도 않는다.
        assertThat(relinked).singleElement()
                .extracting(GithubRepository::getId)
                .isEqualTo(repositoryId);
        assertThat(service.list(userId, projectId)).hasSize(1);
        assertThat(pullRequestRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("이미 끊긴 저장소를 다시 끊으면 404다 — 화면이 낡았다는 뜻이다")
    void secondUnlinkIsNotFound() {
        service.unlink(userId, projectId, repositoryId);

        assertThatThrownBy(() -> service.unlink(userId, projectId, repositoryId))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_REPOSITORY_NOT_FOUND);
    }

    private static Map<Long, RepositorySnapshot> accessible() {
        Map<Long, RepositorySnapshot> map = new LinkedHashMap<>();
        map.put(GITHUB_REPOSITORY_ID, new RepositorySnapshot(GITHUB_REPOSITORY_ID, INSTALLATION_ID,
                "wb", "app", "wb/app", true, "main", "https://github.com/wb/app"));
        return map;
    }

    private static PullRequestWriter.CollectedPullRequestData pullRequest(int number) {
        return new PullRequestWriter.CollectedPullRequestData(
                1000L + number, number, "PR " + number, "본문", "main", "feature",
                "base" + number, "head" + number, "merge" + number,
                OffsetDateTime.parse("2026-01-01T00:00Z"),
                OffsetDateTime.parse("2025-12-01T00:00Z"), 1, 10, 3,
                "https://github.com/wb/app/pull/" + number,
                77L, "octocat", "https://avatar",
                List.of(),
                List.of(new PullRequestWriter.CollectedFileData("src/App.java", null,
                        ChangeStatus.MODIFIED, 10, 3, 13, false)),
                List.of(new PullRequestWriter.CollectedCommitData("sha" + number, "feat: 구현",
                        "octocat", 77L, OffsetDateTime.parse("2025-12-15T00:00Z"))));
    }
}

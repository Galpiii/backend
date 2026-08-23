package com.github.galpiii.galpi.domain.collection.pr;

import com.github.galpiii.galpi.domain.collection.entity.ChangeStatus;
import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.repository.ContributorRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestCommitRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestFileRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR 저장을 실제 Postgres에 붙여 확인한다.
 *
 * <p>여기서만 볼 수 있는 것이 둘이다. {@code pull_request_files}에 diff 원문 컬럼이 정말
 * 없는지는 스키마를 봐야 알 수 있고, contributor upsert의 유니크 제약도 진짜 DB라야 걸린다.
 */
@DisplayName("PR 저장 — 실제 Postgres")
class PullRequestWriterIntegrationTest extends IntegrationTestSupport {

    private static final long INSTALLATION_ID = 100L;

    @Autowired
    private PullRequestWriter writer;
    @Autowired
    private PullRequestRepository pullRequestRepository;
    @Autowired
    private PullRequestFileRepository fileRepository;
    @Autowired
    private PullRequestCommitRepository commitRepository;
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

    private Long repositoryId;
    private Long projectId;

    @BeforeEach
    void setUp() {
        commitRepository.deleteAll();
        fileRepository.deleteAll();
        pullRequestRepository.deleteAll();
        contributorRepository.deleteAll();
        repositoryRepository.deleteAll();
        projectRepository.deleteAll();

        User user = userRepository.save(
                User.ofGithub(System.nanoTime(), "wb", "wb", null, "https://avatar"));
        Project project = projectRepository.save(Project.create(user, "갈피"));
        GithubRepository repository = repositoryRepository.save(
                GithubRepository.link(project, snapshot()));
        projectId = project.getId();
        repositoryId = repository.getId();
    }

    @Test
    @DisplayName("pull_request_files에 diff 원문 컬럼이 없다")
    void pullRequestFilesTableHasNoPatchColumn() {
        List<String> columns = jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_name = 'pull_request_files'
                """, String.class);

        assertThat(columns).doesNotContain("patch");
        assertThat(columns).contains("path", "change_status", "additions", "deletions",
                "patch_omitted");
    }

    @Test
    @DisplayName("PR과 변경 파일, 커밋을 저장한다")
    void savesPullRequestWithFilesAndCommits() {
        writer.save(repositoryId, data(1, 77L, "octocat", List.of()));

        PullRequest saved = pullRequestRepository
                .findByRepositoryIdAndNumber(repositoryId, 1).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("PR 1");
        assertThat(saved.getMergedAt()).isNotNull();
        assertThat(saved.getDataCompleteness()).isEqualTo(DataCompleteness.COMPLETE);
        assertThat(fileRepository.findAllByPullRequestId(saved.getId())).hasSize(1);
        assertThat(commitRepository.findAllByPullRequestId(saved.getId())).hasSize(1);
    }

    @Test
    @DisplayName("삭제된 GitHub 계정이 작성한 PR은 contributor_id 없이 저장된다")
    void savesPullRequestFromDeletedAccount() {
        writer.save(repositoryId, data(1, null, null, List.of()));

        PullRequest saved = pullRequestRepository
                .findByRepositoryIdAndNumber(repositoryId, 1).orElseThrow();
        assertThat(saved.getContributor()).isNull();
        assertThat(contributorRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("같은 작성자의 PR이 여러 개여도 contributor는 하나만 만든다")
    void upsertsContributorOnce() {
        writer.save(repositoryId, data(1, 77L, "octocat", List.of()));
        writer.save(repositoryId, data(2, 77L, "octocat-renamed", List.of()));

        assertThat(contributorRepository.findAll()).hasSize(1);
        assertThat(contributorRepository.findByProjectIdAndGithubUserId(projectId, 77L)
                .orElseThrow().getLogin())
                // login은 표시용이라 최신 값으로 갱신된다. 식별은 github_user_id로만 한다.
                .isEqualTo("octocat-renamed");
    }

    @Test
    @DisplayName("같은 PR을 다시 수집하면 갱신하고 변경 파일을 갈아 끼운다")
    void refreshesExistingPullRequest() {
        writer.save(repositoryId, data(1, 77L, "octocat", List.of()));
        Long firstId = pullRequestRepository
                .findByRepositoryIdAndNumber(repositoryId, 1).orElseThrow().getId();

        writer.save(repositoryId, new PullRequestWriter.CollectedPullRequestData(
                1001L, 1, "PR 1 (수정)", "본문", "main", "feature", "base", "head", "merge",
                OffsetDateTime.parse("2026-01-01T00:00Z"),
                OffsetDateTime.parse("2025-12-01T00:00Z"), 3, 20, 5,
                "https://github.com/wb/app/pull/1", 77L, "octocat", "https://avatar",
                List.of(IncompleteReason.PATCH_OMITTED),
                List.of(new PullRequestWriter.CollectedFileData("src/New.java", null,
                        ChangeStatus.ADDED, 5, 0, 5, true)),
                List.of()));

        assertThat(pullRequestRepository.findAll()).hasSize(1);
        PullRequest refreshed = pullRequestRepository.findById(firstId).orElseThrow();
        assertThat(refreshed.getTitle()).isEqualTo("PR 1 (수정)");
        assertThat(refreshed.getDataCompleteness()).isEqualTo(DataCompleteness.PARTIAL);
        assertThat(refreshed.getIncompleteReasons()).containsExactly(IncompleteReason.PATCH_OMITTED);
        assertThat(fileRepository.findAllByPullRequestId(firstId))
                .extracting(file -> file.getPath())
                .containsExactly("src/New.java");
        assertThat(commitRepository.findAllByPullRequestId(firstId)).isEmpty();
    }

    private static PullRequestWriter.CollectedPullRequestData data(
            int number, Long authorGithubId, String authorLogin,
            List<IncompleteReason> incompleteReasons) {
        return new PullRequestWriter.CollectedPullRequestData(
                1000L + number, number, "PR " + number, "본문", "main", "feature",
                "base" + number, "head" + number, "merge" + number,
                OffsetDateTime.parse("2026-01-01T00:00Z"),
                OffsetDateTime.parse("2025-12-01T00:00Z"), 1, 10, 3,
                "https://github.com/wb/app/pull/" + number,
                authorGithubId, authorLogin, authorGithubId == null ? null : "https://avatar",
                incompleteReasons,
                List.of(new PullRequestWriter.CollectedFileData("src/App.java", null,
                        ChangeStatus.MODIFIED, 10, 3, 13, false)),
                List.of(new PullRequestWriter.CollectedCommitData("sha" + number, "feat: 구현",
                        authorLogin, authorGithubId,
                        OffsetDateTime.parse("2025-12-15T00:00Z"))));
    }

    private static RepositorySnapshot snapshot() {
        return new RepositorySnapshot(11L, INSTALLATION_ID, "wb", "app", "wb/app", true, "main",
                "https://github.com/wb/app");
    }
}

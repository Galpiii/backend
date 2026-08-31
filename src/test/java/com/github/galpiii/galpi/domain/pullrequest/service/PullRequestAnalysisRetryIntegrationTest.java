package com.github.galpiii.galpi.domain.pullrequest.service;

import com.github.galpiii.galpi.domain.collection.entity.Contributor;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.repository.ContributorRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import com.github.galpiii.galpi.domain.consent.exception.AiDataConsentRequiredException;
import com.github.galpiii.galpi.domain.consent.service.AiDataConsentService;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.pullrequest.PullRequestFixture;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실패한 요약 재시도.
 *
 * <p>이 경로가 지켜야 할 것이 둘이다. 돌고 있는 요약을 건드리지 않는 것과, 되돌릴 것이 없어도
 * 실패로 만들지 않는 것. 앞은 워커의 결과를 덮는 문제이고, 뒤는 "이미 다 됐다"가 화면에
 * "실패했다"로 보이는 문제다.
 */
@DisplayName("PR 요약 재시도 — 실제 Postgres")
class PullRequestAnalysisRetryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PullRequestAnalysisRetryService retryService;
    @Autowired
    private PullRequestAnalysisRepository analysisRepository;
    @Autowired
    private PullRequestRepository pullRequestRepository;
    @Autowired
    private ContributorRepository contributorRepository;
    @Autowired
    private GithubRepositoryRepository repositoryRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AiDataConsentService consentService;
    @Autowired
    private ConsentProperties consentProperties;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;
    private Project project;
    private GithubRepository backend;
    private GithubRepository frontend;
    private Contributor contributor;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(PullRequestFixture.user("wb"));
        project = projectRepository.save(Project.create(owner, "갈피"));
        backend = repositoryRepository.save(
                PullRequestFixture.repository(project, "sample-org/backend"));
        frontend = repositoryRepository.save(
                PullRequestFixture.repository(project, "sample-org/frontend"));
        contributor = contributorRepository.save(
                PullRequestFixture.contributor(project, "developerA"));
        consentService.agree(owner.getId(), consentProperties.aiDataVersion());
    }

    @Test
    @DisplayName("실패한 요약을 PENDING으로 되돌리고 시도 횟수를 0으로 만든다")
    void requeuesFailedAnalyses() {
        Long failed = failedAnalysis(backend, 41);

        assertThat(retryService.retry(owner.getId(), project.getId(), null).requeuedCount())
                .isEqualTo(1);

        PullRequestAnalysis analysis = analysisRepository.findById(failed).orElseThrow();
        assertThat(analysis.getStatus()).isEqualTo(PullRequestAnalysisStatus.PENDING);
        assertThat(analysis.getAttempts()).isZero();
        assertThat(analysis.getErrorCode()).isNull();
        assertThat(analysis.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("되돌릴 것이 없어도 에러가 아니다")
    void returnsZeroWhenNothingFailed() {
        completedAnalysis(backend, 41);

        assertThat(retryService.retry(owner.getId(), project.getId(), null).requeuedCount())
                .isZero();
    }

    @Test
    @DisplayName("진행 중인 요약은 건드리지 않는다")
    void leavesInFlightAnalysesAlone() {
        Long running = runningAnalysis(backend, 41);
        Long failed = failedAnalysis(backend, 42);

        assertThat(retryService.retry(owner.getId(), project.getId(), null).requeuedCount())
                .isEqualTo(1);

        // 돌고 있는 요약을 되돌리면 워커가 끝낸 결과와 이 갱신이 서로를 덮는다.
        assertThat(analysisRepository.findById(running).orElseThrow().getAttempts()).isEqualTo(2);
        assertThat(analysisRepository.findById(failed).orElseThrow().getAttempts()).isZero();
    }

    @Test
    @DisplayName("끝난 요약을 다시 만들지 않는다")
    void leavesCompletedAnalysesAlone() {
        Long completed = completedAnalysis(backend, 41);
        failedAnalysis(backend, 42);

        retryService.retry(owner.getId(), project.getId(), null);

        assertThat(analysisRepository.findById(completed).orElseThrow().getStatus())
                .isEqualTo(PullRequestAnalysisStatus.COMPLETED);
    }

    @Test
    @DisplayName("저장소 하나만 좁힐 수 있다")
    void narrowsToRepository() {
        Long backendFailure = failedAnalysis(backend, 41);
        Long frontendFailure = failedAnalysis(frontend, 12);

        assertThat(retryService.retry(owner.getId(), project.getId(), backend.getId())
                .requeuedCount()).isEqualTo(1);

        assertThat(analysisRepository.findById(backendFailure).orElseThrow().getStatus())
                .isEqualTo(PullRequestAnalysisStatus.PENDING);
        assertThat(analysisRepository.findById(frontendFailure).orElseThrow().getStatus())
                .isEqualTo(PullRequestAnalysisStatus.FAILED);
    }

    @Test
    @DisplayName("이 프로젝트의 저장소가 아니면 PROJECT-002다")
    void rejectsForeignRepository() {
        User stranger = userRepository.save(PullRequestFixture.user("someone-else"));
        Project other = projectRepository.save(Project.create(stranger, "남의 프로젝트"));
        GithubRepository foreign = repositoryRepository.save(
                PullRequestFixture.repository(other, "other-org/secret"));

        assertThatThrownBy(() ->
                retryService.retry(owner.getId(), project.getId(), foreign.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_REPOSITORY_NOT_FOUND);
    }

    @Test
    @DisplayName("남의 프로젝트는 404다")
    void rejectsForeignProject() {
        User stranger = userRepository.save(PullRequestFixture.user("someone-else"));

        assertThatThrownBy(() -> retryService.retry(stranger.getId(), project.getId(), null))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("동의가 없으면 큐에 넣지 않는다 — 세션이 있는 유일한 지점이 여기다")
    void requiresConsent() {
        User other = userRepository.save(PullRequestFixture.user("no-consent"));
        Project otherProject = projectRepository.save(Project.create(other, "동의 없는 프로젝트"));

        assertThatThrownBy(() -> retryService.retry(other.getId(), otherProject.getId(), null))
                .isInstanceOf(AiDataConsentRequiredException.class);
    }

    private Long failedAnalysis(GithubRepository repository, int number) {
        PullRequestAnalysis analysis = analysis(repository, number);
        analysis.fail(SummaryFailureCode.SUMMARY_LLM_FAILED, "실패");
        return analysisRepository.saveAndFlush(analysis).getId();
    }

    private Long completedAnalysis(GithubRepository repository, int number) {
        PullRequestAnalysis analysis = analysis(repository, number);
        analysis.complete("요약", ChangeType.FEATURE, "gpt-5-mini");
        return analysisRepository.saveAndFlush(analysis).getId();
    }

    /**
     * 워커가 두 번째 시도로 돌고 있는 상태.
     *
     * <p>선점 쿼리를 부르지 않고 상태를 직접 넣는다. 선점은 트랜잭션 안에서만 유효해서
     * 테스트가 그것을 흉내 내려면 경계를 하나 더 만들어야 하고, 여기서 확인하려는 것은
     * 선점 동작이 아니라 "재시도가 RUNNING 행을 건드리지 않는가"다. 시도 횟수를 남겨
     * 두어야 재시도가 초기화했는지 볼 수 있다.
     */
    private Long runningAnalysis(GithubRepository repository, int number) {
        Long id = analysisRepository.saveAndFlush(analysis(repository, number)).getId();
        jdbcTemplate.update("""
                update pull_request_analyses
                   set status = 'RUNNING', claimed_by = 'worker-1', claimed_at = now(),
                       attempts = 2
                 where id = ?
                """, id);
        return id;
    }

    private PullRequestAnalysis analysis(GithubRepository repository, int number) {
        PullRequest pullRequest = pullRequestRepository.saveAndFlush(
                PullRequestFixture.pullRequest(repository, contributor, number, "feat: " + number));
        return PullRequestAnalysis.pending(pullRequest, 5000L, owner, pullRequest.getHeadSha());
    }
}

package com.github.galpiii.galpi.domain.pullrequest.service;

import com.github.galpiii.galpi.domain.collection.entity.Contributor;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.repository.ContributorRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.consent.config.ConsentProperties;
import com.github.galpiii.galpi.domain.consent.exception.AiDataConsentRequiredException;
import com.github.galpiii.galpi.domain.consent.service.AiDataConsentService;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.event.GithubDisconnectedEvent;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.event.ProjectDeletedEvent;
import com.github.galpiii.galpi.domain.project.event.ProjectRepositoryUnlinkedEvent;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.pullrequest.PullRequestFixture;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.pullrequest.support.SummaryHandoffGuard;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PR 요약 큐 정리 — 실제 Postgres")
class PullRequestAnalysisScopeCancellationIntegrationTest extends IntegrationTestSupport {

    private static final String WORKER_ID = "worker-1";

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
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private SummaryHandoffGuard handoffGuard;
    @Autowired
    private AiDataConsentService consentService;
    @Autowired
    private ConsentProperties consentProperties;

    private User owner;
    private Project project;
    private GithubRepository repository;
    private PullRequestAnalysis pending;
    private PullRequestAnalysis running;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(PullRequestFixture.user("wb"));
        project = projectRepository.save(Project.create(owner, "갈피"));
        repository = repositoryRepository.save(
                PullRequestFixture.repository(project, "sample-org/backend"));
        Contributor contributor = contributorRepository.save(
                PullRequestFixture.contributor(project, "developerA"));
        PullRequest first = pullRequestRepository.save(
                PullRequestFixture.pullRequest(repository, contributor, 41, "feat: 로그인"));
        PullRequest second = pullRequestRepository.save(
                PullRequestFixture.pullRequest(repository, contributor, 42, "fix: 오타"));

        pending = PullRequestAnalysis.pending(first, 5000L, owner, first.getHeadSha());
        running = PullRequestAnalysis.pending(second, 5000L, owner, second.getHeadSha());
        analysisRepository.saveAllAndFlush(List.of(pending, running));

        inTransaction(() -> analysisRepository.claim(
                List.of(running.getId()), WORKER_ID, OffsetDateTime.now().minusMinutes(10)));
    }

    @Test
    @DisplayName("프로젝트 삭제는 PENDING과 RUNNING 요약을 같은 트랜잭션에서 취소한다")
    void cancelsOnProjectDeletion() {
        inTransaction(() -> eventPublisher.publishEvent(new ProjectDeletedEvent(project.getId())));

        assertCancelled(SummaryFailureCode.PROJECT_DELETED);
    }

    @Test
    @DisplayName("저장소 연결 해제는 PENDING과 RUNNING 요약을 취소한다")
    void cancelsOnRepositoryUnlink() {
        inTransaction(() -> eventPublisher.publishEvent(
                new ProjectRepositoryUnlinkedEvent(repository.getId())));

        assertCancelled(SummaryFailureCode.REPOSITORY_UNLINKED);
    }

    @Test
    @DisplayName("GitHub 연결 해제는 그 사용자가 요청한 PENDING과 RUNNING 요약을 취소한다")
    void cancelsOnGithubDisconnect() {
        inTransaction(() -> eventPublisher.publishEvent(new GithubDisconnectedEvent(owner.getId())));

        assertCancelled(SummaryFailureCode.GITHUB_DISCONNECTED);
    }

    @Test
    @DisplayName("관문은 claim·생존·연결·현재 동의를 실제 DB 한 조회에서 판단한다")
    void checksCurrentHandoffState() {
        owner.connectGithub();
        userRepository.saveAndFlush(owner);
        consentService.agree(owner.getId(), consentProperties.aiDataVersion());

        assertThatCode(() -> handoffGuard.check(running.getId(), WORKER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("현재 동의 행이 없으면 같은 관문 조회가 전송을 막는다")
    void blocksWithoutCurrentConsent() {
        owner.connectGithub();
        userRepository.saveAndFlush(owner);

        assertThatThrownBy(() -> handoffGuard.check(running.getId(), WORKER_ID))
                .isInstanceOf(AiDataConsentRequiredException.class);
    }

    private void assertCancelled(SummaryFailureCode reason) {
        List<PullRequestAnalysis> cancelled = analysisRepository.findAll();
        assertThat(cancelled).hasSize(2).allSatisfy(analysis -> {
            assertThat(analysis.getStatus()).isEqualTo(PullRequestAnalysisStatus.CANCELLED);
            assertThat(analysis.getErrorCode()).isEqualTo(reason);
            assertThat(analysis.getClaimedBy()).isNull();
            assertThat(analysis.getClaimedAt()).isNull();
            assertThat(analysis.getNextAttemptAt()).isNull();
        });
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }
}

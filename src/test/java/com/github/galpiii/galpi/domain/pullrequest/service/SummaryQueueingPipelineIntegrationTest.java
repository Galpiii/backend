package com.github.galpiii.galpi.domain.pullrequest.service;

import com.github.galpiii.galpi.domain.collection.entity.Contributor;
import com.github.galpiii.galpi.domain.collection.entity.DataCompleteness;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.pipeline.AnalysisPipelinePort;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot;
import com.github.galpiii.galpi.domain.collection.pipeline.CollectedRepositorySnapshot.CollectedPullRequest;
import com.github.galpiii.galpi.domain.collection.repository.ContributorRepository;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.repository.GithubRepositoryRepository;
import com.github.galpiii.galpi.domain.project.entity.Project;
import com.github.galpiii.galpi.domain.project.event.ProjectRepositoryUnlinkedEvent;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.pullrequest.PullRequestFixture;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 수집 결과가 요약 큐로 넘어가는 지점.
 *
 * <p>이 인계는 <b>행을 만들기만 한다.</b> 여기서 LLM을 부르면 저장소 하나에 PR이 300개까지
 * 오는 만큼 수집이 길어져 installation token 수명과 작업 lease를 넘기고, 실패한 PR만 다시
 * 시도할 방법도 사라진다. 그래서 "LLM을 부르지 않는다"가 이 테스트의 핵심 항목이다.
 */
@DisplayName("요약 인계 — 실제 Postgres")
class SummaryQueueingPipelineIntegrationTest extends IntegrationTestSupport {

    private static final Long INSTALLATION_ID = 5000L;

    @Autowired
    private AnalysisPipelinePort pipeline;
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
    private OpenAIClient openAIClient;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private User owner;
    private GithubRepository repository;
    private Contributor contributor;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(PullRequestFixture.user("wb"));
        Project project = projectRepository.save(Project.create(owner, "갈피"));
        repository = repositoryRepository.save(
                PullRequestFixture.repository(project, "sample-org/backend"));
        contributor = contributorRepository.save(
                PullRequestFixture.contributor(project, "developerA"));
    }

    @Test
    @DisplayName("수집 경로에 끼워지는 구현이 로깅 기본값이 아니라 요약 큐잉이다")
    void realImplementationWins() {
        // LoggingAnalysisPipeline이 @ConditionalOnMissingBean이라 보통 물러나지만, 그 조건은
        // 자동설정용이라 스캔 순서에 기댄다. 순서가 어긋나 로깅 구현이 주입되면 수집은 그대로
        // 성공하고 요약만 조용히 사라진다 -- 그 실패를 여기서 잡는다.
        assertThat(pipeline).isInstanceOf(SummaryQueueingPipeline.class);
    }

    @Nested
    @DisplayName("처음 인계할 때")
    class FirstHandoff {

        @Test
        @DisplayName("PR마다 PENDING 행을 만들고 인계 시점의 installation과 요청자를 고정한다")
        void createsPendingRows() {
            PullRequest first = save(41, "feat: 로그인", "sha41");
            PullRequest second = save(42, "fix: 오타", "sha42");

            pipeline.accept(snapshot(List.of(collected(first), collected(second))));

            List<PullRequestAnalysis> analyses = analysisRepository.findAll();
            assertThat(analyses).hasSize(2)
                    .allSatisfy(analysis -> {
                        assertThat(analysis.getStatus())
                                .isEqualTo(PullRequestAnalysisStatus.PENDING);
                        assertThat(analysis.getInstallationId()).isEqualTo(INSTALLATION_ID);
                        assertThat(analysis.getRequestedBy().getId()).isEqualTo(owner.getId());
                        assertThat(analysis.getAttempts()).isZero();
                        assertThat(analysis.getSummary()).isNull();
                    });
            assertThat(analyses).extracting(PullRequestAnalysis::getHeadSha)
                    .containsExactlyInAnyOrder("sha41", "sha42");
        }

        @Test
        @DisplayName("LLM을 부르지 않는다")
        void doesNotCallLlm() {
            PullRequest pullRequest = save(41, "feat: 로그인", "sha41");

            pipeline.accept(snapshot(List.of(collected(pullRequest))));

            verifyNoInteractions(openAIClient);
        }

        @Test
        @DisplayName("넘어온 PR이 없으면 아무것도 만들지 않는다")
        void ignoresEmptyHandoff() {
            pipeline.accept(snapshot(List.of()));

            assertThat(analysisRepository.findAll()).isEmpty();
        }
    }

    @Nested
    @DisplayName("같은 PR을 다시 수집할 때")
    class Recollection {

        @Test
        @DisplayName("행이 중복되지 않는다")
        void doesNotDuplicateRows() {
            PullRequest pullRequest = save(41, "feat: 로그인", "sha41");

            pipeline.accept(snapshot(List.of(collected(pullRequest))));
            pipeline.accept(snapshot(List.of(collected(pullRequest))));

            assertThat(analysisRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("head가 그대로면 끝난 요약을 다시 만들지 않는다")
        void keepsCompletedSummary() {
            PullRequest pullRequest = save(41, "feat: 로그인", "sha41");
            pipeline.accept(snapshot(List.of(collected(pullRequest))));
            complete(pullRequest);

            pipeline.accept(snapshot(List.of(collected(pullRequest))));

            PullRequestAnalysis analysis =
                    analysisRepository.findByPullRequestId(pullRequest.getId()).orElseThrow();
            assertThat(analysis.getStatus()).isEqualTo(PullRequestAnalysisStatus.COMPLETED);
            assertThat(analysis.getSummary()).isEqualTo("요약");
        }

        @Test
        @DisplayName("head가 그대로면 실패한 요약도 그대로 둔다")
        void keepsFailedSummary() {
            PullRequest pullRequest = save(41, "feat: 로그인", "sha41");
            pipeline.accept(snapshot(List.of(collected(pullRequest))));
            fail(pullRequest);

            pipeline.accept(snapshot(List.of(collected(pullRequest))));

            // 재시도는 사용자가 누르는 것이지 재수집이 대신 정할 일이 아니다.
            assertThat(analysisRepository.findByPullRequestId(pullRequest.getId()).orElseThrow()
                    .getStatus()).isEqualTo(PullRequestAnalysisStatus.FAILED);
        }

        @Test
        @DisplayName("head가 같아도 재설치된 GitHub App 실행 컨텍스트는 갱신한다")
        void refreshesInstallationContextForSameHead() {
            PullRequest pullRequest = save(41, "feat: 로그인", "sha41");
            pipeline.accept(snapshot(INSTALLATION_ID, List.of(collected(pullRequest))));
            fail(pullRequest);

            long reinstalledInstallationId = 9000L;
            pipeline.accept(snapshot(reinstalledInstallationId,
                    List.of(collected(pullRequest))));

            PullRequestAnalysis analysis =
                    analysisRepository.findByPullRequestId(pullRequest.getId()).orElseThrow();
            assertThat(analysis.getStatus()).isEqualTo(PullRequestAnalysisStatus.FAILED);
            assertThat(analysis.getInstallationId()).isEqualTo(reinstalledInstallationId);
            assertThat(analysis.getRequestedBy().getId()).isEqualTo(owner.getId());
        }

        @Test
        @DisplayName("취소된 요약은 실행 근거가 복구된 후 같은 head를 재수집해도 다시 큐에 넣는다")
        void requeuesCancelledAnalysisForSameHead() {
            PullRequest pullRequest = save(41, "feat: 로그인", "sha41");
            pipeline.accept(snapshot(List.of(collected(pullRequest))));
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    eventPublisher.publishEvent(
                            new ProjectRepositoryUnlinkedEvent(repository.getId())));

            assertThat(analysisRepository.findByPullRequestId(pullRequest.getId()).orElseThrow()
                    .getStatus()).isEqualTo(PullRequestAnalysisStatus.CANCELLED);

            pipeline.accept(snapshot(List.of(collected(pullRequest))));

            PullRequestAnalysis requeued =
                    analysisRepository.findByPullRequestId(pullRequest.getId()).orElseThrow();
            assertThat(requeued.getStatus()).isEqualTo(PullRequestAnalysisStatus.PENDING);
            assertThat(requeued.getAttempts()).isZero();
            assertThat(requeued.getErrorCode()).isNull();
        }

        @Test
        @DisplayName("head가 바뀌면 지난 요약을 버리고 다시 큐에 넣는다")
        void requeuesWhenHeadChanged() {
            PullRequest pullRequest = save(41, "feat: 로그인", "sha41");
            pipeline.accept(snapshot(List.of(collected(pullRequest))));
            complete(pullRequest);

            pipeline.accept(snapshot(List.of(new CollectedPullRequest(
                    pullRequest.getId(), 41, "feat: 로그인", "본문", "sha41-new",
                    OffsetDateTime.now(), List.of(), List.of(),
                    DataCompleteness.COMPLETE, List.of()))));

            PullRequestAnalysis analysis =
                    analysisRepository.findByPullRequestId(pullRequest.getId()).orElseThrow();
            assertThat(analysis.getStatus()).isEqualTo(PullRequestAnalysisStatus.PENDING);
            assertThat(analysis.getHeadSha()).isEqualTo("sha41-new");
            assertThat(analysis.getSummary()).isNull();
            assertThat(analysis.getAttempts()).isZero();
        }
    }

    private PullRequest save(int number, String title, String headSha) {
        return pullRequestRepository.save(PullRequestFixture.pullRequest(
                repository, contributor, number, title, headSha));
    }

    private void complete(PullRequest pullRequest) {
        PullRequestAnalysis analysis =
                analysisRepository.findByPullRequestId(pullRequest.getId()).orElseThrow();
        analysis.complete("요약", ChangeType.FEATURE, "gpt-5-mini");
        analysisRepository.saveAndFlush(analysis);
    }

    private void fail(PullRequest pullRequest) {
        PullRequestAnalysis analysis =
                analysisRepository.findByPullRequestId(pullRequest.getId()).orElseThrow();
        analysis.fail(SummaryFailureCode.SUMMARY_LLM_FAILED, "실패");
        analysisRepository.saveAndFlush(analysis);
    }

    private CollectedPullRequest collected(PullRequest pullRequest) {
        return new CollectedPullRequest(pullRequest.getId(), pullRequest.getNumber(),
                pullRequest.getTitle(), pullRequest.getBody(), pullRequest.getHeadSha(),
                pullRequest.getMergedAt(), List.of(), List.of(),
                DataCompleteness.COMPLETE, List.of());
    }

    private CollectedRepositorySnapshot snapshot(List<CollectedPullRequest> pullRequests) {
        return snapshot(INSTALLATION_ID, pullRequests);
    }

    private CollectedRepositorySnapshot snapshot(long installationId,
                                                  List<CollectedPullRequest> pullRequests) {
        return new CollectedRepositorySnapshot(
                repository.getId(), repository.getGithubRepositoryId(), installationId,
                owner.getId(), repository.getFullName(), "commit-sha",
                List.of(), List.of(), List.of(), pullRequests,
                DataCompleteness.COMPLETE, List.of());
    }
}

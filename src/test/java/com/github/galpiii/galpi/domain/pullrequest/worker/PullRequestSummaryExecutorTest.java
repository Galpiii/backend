package com.github.galpiii.galpi.domain.pullrequest.worker;

import com.github.galpiii.galpi.ai.PullRequestSummaryAiService;
import com.github.galpiii.galpi.ai.dto.PullRequestSummaryResult;
import com.github.galpiii.galpi.ai.exception.PullRequestSummaryAiException;
import com.github.galpiii.galpi.ai.exception.PullRequestSummaryInvalidResponseException;
import com.github.galpiii.galpi.ai.support.AiFailureKind;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestCommitRepository;
import com.github.galpiii.galpi.domain.consent.exception.AiDataConsentRequiredException;
import com.github.galpiii.galpi.domain.github.client.GithubCollectionClient;
import com.github.galpiii.galpi.domain.github.client.RateLimitRecorder;
import com.github.galpiii.galpi.domain.github.client.RateLimitSnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubInstallationUnavailableException;
import com.github.galpiii.galpi.domain.github.exception.GithubRateLimitedException;
import com.github.galpiii.galpi.domain.github.exception.GithubRepositoryUnavailableException;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationTokenService;
import com.github.galpiii.galpi.domain.pullrequest.config.SummaryProperties;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.pullrequest.service.PullRequestAnalysisWriter;
import com.github.galpiii.galpi.domain.pullrequest.support.SummaryHandoffAbandonedException;
import com.github.galpiii.galpi.domain.pullrequest.support.SummaryHandoffGuard;
import com.github.galpiii.galpi.domain.pullrequest.support.SummaryInputAssembler;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

/**
 * 선점한 요약 하나가 어떤 결말을 맞는지.
 *
 * <p>이 클래스가 정하는 것은 사실상 하나다 -- 실패했을 때 <b>다시 시도할지 끝낼지</b>. 그 판단이
 * 틀리면 재시도로 풀릴 실패가 화면에 "분석 실패"로 굳거나, 풀리지 않을 실패가 시도 상한까지
 * GitHub 호출과 LLM 비용을 태운다.
 */
@DisplayName("PullRequestSummaryExecutor — 요약 실행")
class PullRequestSummaryExecutorTest {

    private static final String WORKER_ID = "worker-1";
    private static final String TOKEN = "ghs_token";
    private static final Long ANALYSIS_ID = 100L;
    private static final Long INSTALLATION_ID = 5000L;
    private static final Long USER_ID = 7L;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_FILE_PAGES = 2;

    private final PullRequestAnalysisRepository analysisRepository =
            mock(PullRequestAnalysisRepository.class);
    private final PullRequestCommitRepository commitRepository =
            mock(PullRequestCommitRepository.class);
    private final GithubInstallationTokenService tokenService =
            mock(GithubInstallationTokenService.class);
    private final GithubCollectionClient client = mock(GithubCollectionClient.class);
    private final PullRequestSummaryAiService aiService = mock(PullRequestSummaryAiService.class);
    private final SummaryInputAssembler inputAssembler = mock(SummaryInputAssembler.class);
    private final SummaryHandoffGuard handoffGuard = mock(SummaryHandoffGuard.class);
    private final PullRequestAnalysisWriter writer = mock(PullRequestAnalysisWriter.class);
    private final RateLimitRecorder rateLimitRecorder = mock(RateLimitRecorder.class);

    private PullRequestSummaryExecutor executor;
    private PullRequestAnalysis analysis;

    @BeforeEach
    void setUp() {
        executor = new PullRequestSummaryExecutor(analysisRepository, commitRepository,
                tokenService, client, aiService, inputAssembler, handoffGuard, writer,
                rateLimitRecorder, properties(), pool());

        analysis = analysis(1);
        given(analysisRepository.findAllForExecution(
                List.of(ANALYSIS_ID), WORKER_ID, PullRequestAnalysisStatus.RUNNING))
                .willReturn(List.of(analysis));
        given(tokenService.issue(eq(INSTALLATION_ID), any())).willReturn(TOKEN);
        given(client.listFiles(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .willReturn(new GithubCollectionClient.PagedResult<>(List.of(), false));
        given(commitRepository.findAllByPullRequestId(anyLong())).willReturn(List.of());
        given(inputAssembler.assemble(anyString(), any(), any(), any())).willReturn("입력");
        given(aiService.model()).willReturn("gpt-5-mini");
    }

    @Test
    @DisplayName("성공하면 요약·변경 유형·모델을 기록한다")
    void completesOnSuccess() {
        given(aiService.summarize("입력"))
                .willReturn(new PullRequestSummaryResult("회원가입 처리를 추가했습니다.", "FEATURE"));

        executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

        verify(writer).complete(ANALYSIS_ID, WORKER_ID, "회원가입 처리를 추가했습니다.",
                ChangeType.FEATURE, "gpt-5-mini");
    }

    @Test
    @DisplayName("변경 파일은 요약 전용 페이지 상한까지만 받는다 — 뒷부분은 입력 상한에서 잘린다")
    void limitsFilePagesForSummary() {
        // 페이지 하나가 GitHub 호출 하나다. 수집 쪽 상한(30페이지)을 그대로 쓰면 큰 PR
        // 몇 건이 rate limit 여유를 버려질 내용으로 깎는다.
        given(aiService.summarize("입력"))
                .willReturn(new PullRequestSummaryResult("요약입니다.", "FEATURE"));

        executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

        verify(client).listFiles(TOKEN, "sample-org", "backend", 42, MAX_FILE_PAGES);
    }

    @Test
    @DisplayName("외부 전송 직전에 현재 claim과 실행 근거를 확인한다")
    void checksHandoffBeforeSending() {
        given(aiService.summarize("입력"))
                .willReturn(new PullRequestSummaryResult("요약입니다.", "FEATURE"));

        executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

        var order = inOrder(handoffGuard, aiService);
        order.verify(handoffGuard).check(ANALYSIS_ID, WORKER_ID);
        order.verify(aiService).summarize("입력");
    }

    @Test
    @DisplayName("같은 사용자·installation의 배치여도 PR마다 관문을 다시 통과한다")
    void checksHandoffForEveryPullRequest() {
        PullRequestAnalysis first = analysis(ANALYSIS_ID, INSTALLATION_ID, 900L, 1);
        PullRequestAnalysis second = analysis(101L, INSTALLATION_ID, 901L, 1);
        given(analysisRepository.findAllForExecution(
                List.of(ANALYSIS_ID, 101L), WORKER_ID,
                PullRequestAnalysisStatus.RUNNING)).willReturn(List.of(first, second));
        given(aiService.summarize("입력"))
                .willReturn(new PullRequestSummaryResult("요약", "FEATURE"));

        executor.execute(List.of(ANALYSIS_ID, 101L), WORKER_ID);

        verify(handoffGuard).check(ANALYSIS_ID, WORKER_ID);
        verify(handoffGuard).check(101L, WORKER_ID);
    }

    @Nested
    @DisplayName("실패했을 때")
    class Failures {

        @Test
        @DisplayName("시도 상한 전이면 backoff를 두고 다음 폴링이 다시 집게 한다")
        void defersBeforeMaxAttempts() {
            given(aiService.summarize("입력")).willThrow(new PullRequestSummaryAiException(
                    AiFailureKind.RETRIES_EXHAUSTED, "실패", null));

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(writer).deferForRetry(eq(ANALYSIS_ID), eq(WORKER_ID), any());
            verify(writer, never()).fail(anyLong(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("시도 상한에 닿으면 마지막 사유를 그대로 남기고 끝낸다")
        void failsAtMaxAttempts() {
            analysis = analysis(MAX_ATTEMPTS);
            given(analysisRepository.findAllForExecution(
                    List.of(ANALYSIS_ID), WORKER_ID, PullRequestAnalysisStatus.RUNNING))
                    .willReturn(List.of(analysis));
            given(aiService.summarize("입력")).willThrow(new PullRequestSummaryAiException(
                    AiFailureKind.RETRIES_EXHAUSTED, "실패", null));

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            // MAX_ATTEMPTS_EXCEEDED로 덮지 않는다. 덮으면 무엇 때문에 못 했는지가 사라진다.
            verify(writer).fail(eq(ANALYSIS_ID), eq(WORKER_ID),
                    eq(SummaryFailureCode.SUMMARY_LLM_FAILED), any());
        }

        @Test
        @DisplayName("예산이 끝난 실패는 시간 초과로 구분해 남긴다")
        void distinguishesBudgetExhaustion() {
            analysis = analysis(MAX_ATTEMPTS);
            given(analysisRepository.findAllForExecution(
                    List.of(ANALYSIS_ID), WORKER_ID, PullRequestAnalysisStatus.RUNNING))
                    .willReturn(List.of(analysis));
            given(aiService.summarize("입력")).willThrow(new PullRequestSummaryAiException(
                    AiFailureKind.BUDGET_EXHAUSTED, "예산 초과", null));

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(writer).fail(eq(ANALYSIS_ID), eq(WORKER_ID),
                    eq(SummaryFailureCode.SUMMARY_LLM_TIMEOUT), any());
        }

        @Test
        @DisplayName("인터럽트는 즉시 실패로 끝내 자동 재시도 루프를 만들지 않는다")
        void failsImmediatelyWhenInterrupted() {
            given(aiService.summarize("입력")).willThrow(new PullRequestSummaryAiException(
                    AiFailureKind.INTERRUPTED, "인터럽트", null));

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(writer).fail(eq(ANALYSIS_ID), eq(WORKER_ID),
                    eq(SummaryFailureCode.SUMMARY_LLM_FAILED), any());
            verify(writer, never()).deferForRetry(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("응답이 저장 규칙을 어기면 재시도하지 않고 바로 끝낸다")
        void failsImmediatelyOnInvalidResponse() {
            given(aiService.summarize("입력"))
                    .willThrow(new PullRequestSummaryInvalidResponseException("300자 초과"));

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(writer).fail(eq(ANALYSIS_ID), eq(WORKER_ID),
                    eq(SummaryFailureCode.SUMMARY_RESPONSE_INVALID), any());
        }

        @Test
        @DisplayName("스키마에 없는 변경 유형도 잘못된 응답으로 본다")
        void rejectsUnknownChangeType() {
            given(aiService.summarize("입력"))
                    .willReturn(new PullRequestSummaryResult("요약입니다.", "UNKNOWN"));

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            // OTHER로 접지 않는다. OTHER는 "어디에도 안 맞는 변경"이지 "규칙을 어겼다"가 아니다.
            verify(writer).fail(eq(ANALYSIS_ID), eq(WORKER_ID),
                    eq(SummaryFailureCode.SUMMARY_RESPONSE_INVALID), any());
            verify(writer, never()).complete(anyLong(), anyString(), anyString(), any(),
                    anyString());
        }

        @Test
        @DisplayName("동의가 사라졌으면 재시도하지 않고 CONSENT_REVOKED로 끝낸다")
        void failsOnRevokedConsent() {
            willThrow(new AiDataConsentRequiredException())
                    .given(handoffGuard).check(ANALYSIS_ID, WORKER_ID);

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(writer).fail(eq(ANALYSIS_ID), eq(WORKER_ID),
                    eq(SummaryFailureCode.CONSENT_REVOKED), any());
            verify(aiService, never()).summarize(anyString());
        }

        @Test
        @DisplayName("프로젝트나 연결이 사라졌으면 전송하지 않고 CANCELLED로 접는다")
        void cancelsWhenHandoffBasisDisappears() {
            willThrow(new SummaryHandoffAbandonedException(
                    SummaryFailureCode.REPOSITORY_UNLINKED))
                    .given(handoffGuard).check(ANALYSIS_ID, WORKER_ID);

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(writer).cancel(ANALYSIS_ID, WORKER_ID,
                    SummaryFailureCode.REPOSITORY_UNLINKED);
            verify(aiService, never()).summarize(anyString());
        }

        @Test
        @DisplayName("저장소에 접근할 수 없으면 재시도하지 않는다")
        void failsOnInaccessibleRepository() {
            given(client.listFiles(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .willThrow(new GithubRepositoryUnavailableException());

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(writer).fail(eq(ANALYSIS_ID), eq(WORKER_ID),
                    eq(SummaryFailureCode.REPOSITORY_INACCESSIBLE), any());
        }

        @Test
        @DisplayName("installation token을 못 받으면 그 설치의 요약을 전부 끝낸다")
        void failsWholeGroupWhenTokenIssuanceFails() {
            given(tokenService.issue(eq(INSTALLATION_ID), any()))
                    .willThrow(new GithubInstallationUnavailableException());

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(writer).fail(eq(ANALYSIS_ID), eq(WORKER_ID),
                    eq(SummaryFailureCode.REPOSITORY_INACCESSIBLE), any());
        }

        @Test
        @DisplayName("GitHub 5xx로 token 발급이 실패하면 backoff 후 재시도한다")
        void retriesTemporaryTokenFailure() {
            given(tokenService.issue(eq(INSTALLATION_ID), any()))
                    .willThrow(new GithubApiException(ErrorCode.GITHUB_API_ERROR, 503));

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(writer).deferForRetry(eq(ANALYSIS_ID), eq(WORKER_ID), any());
            verify(writer, never()).fail(anyLong(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("권한 거부로 token 발급이 실패하면 즉시 종료한다")
        void failsPermanentTokenFailure() {
            given(tokenService.issue(eq(INSTALLATION_ID), any()))
                    .willThrow(new GithubApiException(ErrorCode.GITHUB_API_ERROR, 403));

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(writer).fail(eq(ANALYSIS_ID), eq(WORKER_ID),
                    eq(SummaryFailureCode.REPOSITORY_INACCESSIBLE), any());
        }

        @Test
        @DisplayName("캐시된 token이 거부되면 한 번 무효화·재발급해 같은 PR을 재개한다")
        void refreshesRejectedTokenOnce() {
            String refreshed = "ghs_refreshed";
            given(tokenService.issue(eq(INSTALLATION_ID), any()))
                    .willReturn(TOKEN, refreshed);
            given(client.listFiles(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .willThrow(new GithubInstallationUnavailableException())
                    .willReturn(new GithubCollectionClient.PagedResult<>(List.of(), false));
            given(aiService.summarize("입력"))
                    .willReturn(new PullRequestSummaryResult("요약", "FEATURE"));

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(tokenService).invalidate(INSTALLATION_ID, List.of(900L));
            verify(tokenService, org.mockito.Mockito.times(2))
                    .issue(eq(INSTALLATION_ID), any());
            verify(writer).complete(eq(ANALYSIS_ID), eq(WORKER_ID), anyString(),
                    eq(ChangeType.FEATURE), anyString());
        }
    }

    @Nested
    @DisplayName("rate limit")
    class RateLimited {

        @Test
        @DisplayName("걸리면 기다리지 않고 PENDING으로 되돌린다")
        void releasesToPendingOnRateLimit() {
            given(client.listFiles(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .willThrow(new GithubRateLimitedException(60));

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            // 실패가 아니다. 시도 횟수를 태우지 않고 다음 폴링이 다시 집는다.
            verify(writer).deferForRateLimit(eq(ANALYSIS_ID), eq(WORKER_ID), any());
            verify(writer, never()).fail(anyLong(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("남은 호출 수가 임계 이하면 시작하지도 않는다")
        void doesNotStartWhenNearLimit() {
            given(rateLimitRecorder.latest(TOKEN, "core"))
                    .willReturn(new RateLimitSnapshot(5000, 10, 4990, Instant.now(), "core"));

            executor.execute(List.of(ANALYSIS_ID), WORKER_ID);

            verify(writer).deferForRateLimit(eq(ANALYSIS_ID), eq(WORKER_ID), any());
            verify(client, never()).listFiles(anyString(), anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("한 installation의 rate limit이 다음 installation 그룹을 막지 않는다")
        void isolatesRateLimitPerInstallation() {
            PullRequestAnalysis first = analysis(ANALYSIS_ID, INSTALLATION_ID, 900L, 1);
            PullRequestAnalysis second = analysis(101L, 6000L, 901L, 1);
            given(analysisRepository.findAllForExecution(
                    List.of(ANALYSIS_ID, 101L), WORKER_ID,
                    PullRequestAnalysisStatus.RUNNING)).willReturn(List.of(first, second));
            given(tokenService.issue(eq(INSTALLATION_ID), any())).willReturn("token-one");
            given(tokenService.issue(eq(6000L), any())).willReturn("token-two");
            given(client.listFiles(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .willAnswer(invocation -> {
                        if ("token-one".equals(invocation.getArgument(0))) {
                            throw new GithubRateLimitedException(60);
                        }
                        return new GithubCollectionClient.PagedResult<>(List.of(), false);
                    });
            given(aiService.summarize("입력"))
                    .willReturn(new PullRequestSummaryResult("요약", "FEATURE"));

            executor.execute(List.of(ANALYSIS_ID, 101L), WORKER_ID);

            verify(writer).deferForRateLimit(eq(ANALYSIS_ID), eq(WORKER_ID), any());
            verify(writer).complete(eq(101L), eq(WORKER_ID), anyString(),
                    eq(ChangeType.FEATURE), anyString());
        }
    }

    private PullRequestAnalysis analysis(int attempts) {
        return analysis(ANALYSIS_ID, INSTALLATION_ID, 900L, attempts);
    }

    private PullRequestAnalysis analysis(Long analysisId, Long installationId,
                                         Long githubRepositoryId, int attempts) {
        GithubRepository repository = mock(GithubRepository.class);
        given(repository.getOwner()).willReturn("sample-org");
        given(repository.getName()).willReturn("backend");
        given(repository.getFullName()).willReturn("sample-org/backend");
        given(repository.getGithubRepositoryId()).willReturn(githubRepositoryId);

        PullRequest pullRequest = mock(PullRequest.class);
        given(pullRequest.getId()).willReturn(analysisId + 10);
        given(pullRequest.getNumber()).willReturn(42);
        given(pullRequest.getRepository()).willReturn(repository);

        User user = mock(User.class);
        given(user.getId()).willReturn(USER_ID);

        PullRequestAnalysis mocked = mock(PullRequestAnalysis.class);
        given(mocked.getId()).willReturn(analysisId);
        given(mocked.getInstallationId()).willReturn(installationId);
        given(mocked.getPullRequest()).willReturn(pullRequest);
        given(mocked.getRequestedBy()).willReturn(user);
        given(mocked.getAttempts()).willReturn(attempts);
        return mocked;
    }

    private static SummaryProperties properties() {
        return new SummaryProperties(
                new SummaryProperties.Worker(true, Duration.ofSeconds(5), Duration.ofMinutes(10),
                        Duration.ofSeconds(5), MAX_ATTEMPTS, 10, 2),
                60000, 8000, 90000, MAX_FILE_PAGES);
    }

    /** 실제 풀을 쓴다. 배치가 끝날 때까지 기다리는 동작까지 함께 확인하려는 것이다. */
    private static ThreadPoolTaskExecutor pool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.initialize();
        return executor;
    }
}

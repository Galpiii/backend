package com.github.galpiii.galpi.domain.pullrequest.worker;

import com.github.galpiii.galpi.ai.PullRequestSummaryAiService;
import com.github.galpiii.galpi.ai.dto.PullRequestSummaryResult;
import com.github.galpiii.galpi.ai.exception.PullRequestSummaryAiException;
import com.github.galpiii.galpi.ai.exception.PullRequestSummaryInvalidResponseException;
import com.github.galpiii.galpi.ai.support.AiFailureKind;
import com.github.galpiii.galpi.domain.collection.entity.PullRequest;
import com.github.galpiii.galpi.domain.collection.entity.PullRequestCommit;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestCommitRepository;
import com.github.galpiii.galpi.domain.consent.exception.AiDataConsentRequiredException;
import com.github.galpiii.galpi.domain.github.client.GithubCollectionClient;
import com.github.galpiii.galpi.domain.github.client.RateLimitRecorder;
import com.github.galpiii.galpi.domain.github.client.RateLimitSnapshot;
import com.github.galpiii.galpi.domain.github.client.dto.GithubPullRequestFileResponse;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.exception.GithubInstallationUnavailableException;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubRateLimitedException;
import com.github.galpiii.galpi.domain.github.exception.GithubRepositoryUnavailableException;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationTokenService;
import com.github.galpiii.galpi.domain.pullrequest.config.SummaryProperties;
import com.github.galpiii.galpi.domain.pullrequest.config.SummaryWorkerConfig;
import com.github.galpiii.galpi.domain.pullrequest.entity.ChangeType;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysis;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.entity.SummaryFailureCode;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.pullrequest.service.PullRequestAnalysisWriter;
import com.github.galpiii.galpi.domain.pullrequest.support.SummaryInputAssembler;
import com.github.galpiii.galpi.domain.pullrequest.support.SummaryHandoffAbandonedException;
import com.github.galpiii.galpi.domain.pullrequest.support.SummaryHandoffGuard;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.util.LogSafe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 선점한 요약들을 끝까지 처리한다.
 *
 * <p>사용자 세션이 없는 자리다. {@code pull_request_analyses}에 고정해 둔
 * {@code installation_id}와 {@code requested_by}만 보고 동작한다.
 *
 * <p>{@code installation_id}별로 묶어 토큰을 한 번씩만 발급한다. 요약마다 발급하면 같은 설치에
 * 대해 같은 토큰을 수백 번 만들게 되고, 캐시 키도 저장소마다 갈라져 캐시가 무의미해진다.
 *
 * <p>실제 AI 전송 직전에는 매 PR마다 {@link SummaryHandoffGuard}를 통과한다. 배치가 몇 분
 * 걸리는 동안 동의를 철회하거나 프로젝트·저장소·GitHub 연결이 사라질 수 있어서,
 * 배치나 사용자 단위로 결과를 캐시하지 않는다.
 *
 * <p>rate limit에 걸리면 <b>기다리지 않는다.</b> 남은 것을 PENDING으로 되돌리되,
 * GitHub이 알려 준 reset 시각 전에는 다시 선점하지 않도록 예약한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullRequestSummaryExecutor {

    /** GitHub이 REST 호출에 붙이는 rate limit 버킷 이름. */
    private static final String CORE_RESOURCE = "core";

    /**
     * 남은 호출 수가 이 값 이하면 다음 요약을 시작하지 않는다.
     *
     * <p>수집 쪽(기본 1,000)보다 훨씬 작다. 요약 하나가 쓰는 호출은 원칙적으로 한 번이라,
     * 수집처럼 "절반쯤 하다 버리는" 낭비가 없다. 여유를 크게 잡으면 아직 돌 수 있는 요약을
     * 괜히 미루게 된다.
     */
    private static final int RATE_LIMIT_THRESHOLD = 50;

    private final PullRequestAnalysisRepository analysisRepository;
    private final PullRequestCommitRepository commitRepository;
    private final GithubInstallationTokenService tokenService;
    private final GithubCollectionClient client;
    private final PullRequestSummaryAiService aiService;
    private final SummaryInputAssembler inputAssembler;
    private final SummaryHandoffGuard handoffGuard;
    private final PullRequestAnalysisWriter writer;
    private final RateLimitRecorder rateLimitRecorder;
    private final SummaryProperties properties;

    @Qualifier(SummaryWorkerConfig.EXECUTOR)
    private final ThreadPoolTaskExecutor pool;

    public void execute(List<Long> claimedIds, String workerId) {
        List<PullRequestAnalysis> analyses = analysisRepository.findAllForExecution(
                claimedIds, workerId, PullRequestAnalysisStatus.RUNNING);
        if (analyses.isEmpty()) {
            return;
        }

        List<PullRequestAnalysis> executable = new ArrayList<>();
        for (PullRequestAnalysis analysis : analyses) {
            if (analysis.getAttempts() > properties.worker().maxAttempts()) {
                log.warn("[요약] 시도 상한({})을 넘어 실패로 끝낸다 analysisId={}",
                        properties.worker().maxAttempts(), analysis.getId());
                writer.fail(analysis.getId(), workerId,
                        SummaryFailureCode.MAX_ATTEMPTS_EXCEEDED,
                        "시도 상한을 초과했습니다.");
            } else {
                executable.add(analysis);
            }
        }

        for (Map.Entry<Long, List<PullRequestAnalysis>> group
                : groupByInstallation(executable).entrySet()) {
            processGroup(group.getKey(), group.getValue(), workerId);
        }
    }

    private void processGroup(Long installationId, List<PullRequestAnalysis> analyses,
                              String workerId) {
        List<Long> githubRepositoryIds = analyses.stream()
                .map(analysis -> analysis.getPullRequest().getRepository().getGithubRepositoryId())
                .distinct()
                .toList();

        InstallationToken token;
        try {
            token = new InstallationToken(installationId, githubRepositoryIds,
                    tokenService.issue(installationId, githubRepositoryIds));
        } catch (GithubRateLimitedException e) {
            OffsetDateTime retryAt = retryAt(e);
            deferAll(analyses, workerId, retryAt);
            return;
        } catch (GithubInstallationUnavailableException e) {
            log.warn("[요약] installation token을 발급하지 못해 그룹 전체를 실패 처리한다 "
                    + "installationId={} code={}", installationId, e.getErrorCode().getCode());
            failAll(analyses, workerId, SummaryFailureCode.REPOSITORY_INACCESSIBLE,
                    e.getErrorCode().getMessage());
            return;
        } catch (GithubApiException e) {
            if (e.isTemporary()) {
                retryAll(analyses, workerId, SummaryFailureCode.PATCH_UNAVAILABLE,
                        e.getErrorCode().getMessage());
            } else {
                failAll(analyses, workerId, SummaryFailureCode.REPOSITORY_INACCESSIBLE,
                        e.getErrorCode().getMessage());
            }
            return;
        } catch (GlobalException e) {
            failAll(analyses, workerId, SummaryFailureCode.REPOSITORY_INACCESSIBLE,
                    e.getErrorCode().getMessage());
            return;
        }

        // GitHub rate limit은 installation 단위다. 한 installation의 제한이 다음 그룹까지
        // 번지지 않도록 공유 상태도 이 그룹 안에만 둔다.
        AtomicReference<OffsetDateTime> rateLimitedUntil = new AtomicReference<>();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (PullRequestAnalysis analysis : analyses) {
            tasks.add(CompletableFuture.runAsync(
                    () -> summarizeOne(analysis, token, workerId, rateLimitedUntil), pool));
        }
        // 배치가 끝날 때까지 기다린다. lease 연장이 이 배치를 기준으로 돌고 있어서, 기다리지
        // 않고 다음 폴링으로 넘어가면 아직 돌고 있는 요약의 lease를 아무도 갱신하지 않는다.
        try {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        } catch (RuntimeException e) {
            // 각 작업이 이미 자기 실패를 기록한다. 여기까지 온 것은 기록 자체가 실패한
            // 경우이고, 그때 남은 행은 RUNNING으로 남아 lease 만료 뒤 다시 집힌다.
            log.error("[요약] 배치 처리 중 처리되지 않은 예외 installationId={} cause={}",
                    installationId, e.getClass().getSimpleName());
        }
    }

    private void summarizeOne(PullRequestAnalysis analysis, InstallationToken token,
                              String workerId,
                              AtomicReference<OffsetDateTime> rateLimitedUntil) {
        Long analysisId = analysis.getId();
        PullRequest pullRequest = analysis.getPullRequest();
        GithubRepository repository = pullRequest.getRepository();

        if (rateLimitedUntil.get() != null) {
            writer.deferForRateLimit(analysisId, workerId, rateLimitedUntil.get());
            return;
        }
        try {
            OffsetDateTime retryAt = nearRateLimitRetryAt(token.current());
            if (retryAt != null) {
                log.warn("[요약] 남은 호출 수가 임계({}) 이하라 요약을 미룬다 analysisId={}",
                        RATE_LIMIT_THRESHOLD, analysisId);
                rememberLatest(rateLimitedUntil, retryAt);
                writer.deferForRateLimit(analysisId, workerId, retryAt);
                return;
            }

            // diff는 저장하지 않으므로 여기서 다시 받는다. 이 값은 메모리에서 LLM으로만 가고
            // 어떤 테이블에도 들어가지 않는다.
            List<GithubPullRequestFileResponse> files = listFiles(
                    token, repository, pullRequest.getNumber());
            List<PullRequestCommit> commits =
                    commitRepository.findAllByPullRequestId(pullRequest.getId());

            String input = inputAssembler.assemble(
                    repository.getFullName(), pullRequest, commits, files);

            // GitHub에서 갈피로 받아 오는 것은 동의 대상이 아니다. 실제 외부 AI 전송 직전에
            // 현재 claim·동의·프로젝트·저장소·GitHub 연결 상태를 다시 확인한다.
            handoffGuard.check(analysisId, workerId);
            PullRequestSummaryResult result = aiService.summarize(input);

            writer.complete(analysisId, workerId, result.summary(),
                    toChangeType(result.changeType()), aiService.model());
            log.info("[요약] 완료 analysisId={} number={}", analysisId, pullRequest.getNumber());
        } catch (AiDataConsentRequiredException e) {
            // 재시도로 풀리지 않는다. 사용자가 다시 동의한 뒤 재요약을 눌러야 한다.
            log.warn("[요약] 외부 전송 동의가 없어 요약을 접는다 analysisId={}", analysisId);
            writer.fail(analysisId, workerId, SummaryFailureCode.CONSENT_REVOKED,
                    e.getErrorCode().getMessage());
        } catch (SummaryHandoffAbandonedException e) {
            if (e.getReason() != null) {
                writer.cancel(analysisId, workerId, e.getReason());
            }
            log.info("[요약] 전송 직전 실행 근거가 사라져 중단한다 analysisId={} reason={}",
                    analysisId, e.getReason());
        } catch (GithubRateLimitedException e) {
            log.warn("[요약] rate limit으로 배치를 접는다 analysisId={} retryAfter={}s",
                    analysisId, e.getRetryAfterSeconds());
            OffsetDateTime retryAt = retryAt(e);
            rememberLatest(rateLimitedUntil, retryAt);
            writer.deferForRateLimit(analysisId, workerId, retryAt);
        } catch (GithubRepositoryUnavailableException | GithubInstallationUnavailableException e) {
            writer.fail(analysisId, workerId, SummaryFailureCode.REPOSITORY_INACCESSIBLE,
                    e.getErrorCode().getMessage());
        } catch (GithubApiException e) {
            if (e.isTemporary()) {
                retryOrFail(analysis, workerId, SummaryFailureCode.PATCH_UNAVAILABLE,
                        e.getErrorCode().getMessage());
            } else {
                writer.fail(analysisId, workerId, SummaryFailureCode.REPOSITORY_INACCESSIBLE,
                        e.getErrorCode().getMessage());
            }
        } catch (PullRequestSummaryInvalidResponseException e) {
            writer.fail(analysisId, workerId, SummaryFailureCode.SUMMARY_RESPONSE_INVALID,
                    e.getMessage());
        } catch (PullRequestSummaryAiException e) {
            handleAiFailure(analysis, workerId, e);
        } catch (GlobalException e) {
            retryOrFail(analysis, workerId, SummaryFailureCode.PATCH_UNAVAILABLE,
                    e.getErrorCode().getMessage());
        } catch (RuntimeException e) {
            log.warn("[요약] 예상치 못한 이유로 실패했다 analysisId={} cause={}",
                    analysisId, e.getClass().getSimpleName());
            retryOrFail(analysis, workerId, SummaryFailureCode.SUMMARY_LLM_FAILED,
                    e.getClass().getSimpleName());
        }
    }

    private List<GithubPullRequestFileResponse> listFiles(InstallationToken token,
                                                          GithubRepository repository,
                                                          int pullRequestNumber) {
        String attempted = token.current();
        try {
            return client.listFiles(attempted, repository.getOwner(), repository.getName(),
                    pullRequestNumber).items();
        } catch (GithubInstallationUnavailableException first) {
            // 캐시된 installation token이 폐기됐을 수 있다. 그룹에서 정확히 한 번만 캐시를
            // 비우고 재발급하며, 병렬 task들은 그 결과를 공유한다.
            String refreshed = token.refreshAfterRejection(attempted);
            return client.listFiles(refreshed, repository.getOwner(), repository.getName(),
                    pullRequestNumber).items();
        }
    }

    /**
     * 다시 걸어 볼 만한 실패를 처리한다.
     *
     * <p>시도 횟수는 선점할 때 이미 올라가 있다. 상한에 닿았으면 마지막 실패 사유를 그대로
     * 남긴다 -- {@code MAX_ATTEMPTS_EXCEEDED}로 덮으면 무엇 때문에 못 했는지가 사라지고,
     * 화면에는 "여러 번 실패했다"는 동어반복만 남는다.
     */
    private void retryOrFail(PullRequestAnalysis analysis, String workerId,
                             SummaryFailureCode code, String message) {
        if (analysis.getAttempts() >= properties.worker().maxAttempts()) {
            writer.fail(analysis.getId(), workerId, code, LogSafe.text(message));
            return;
        }
        writer.deferForRetry(analysis.getId(), workerId, retryAt(analysis.getAttempts()));
    }

    private void handleAiFailure(PullRequestAnalysis analysis, String workerId,
                                 PullRequestSummaryAiException failure) {
        SummaryFailureCode code = failureCodeOf(failure);
        switch (failure.getKind()) {
            case CALL_FAILED, INTERRUPTED -> writer.fail(analysis.getId(), workerId, code,
                    LogSafe.text(failure.getMessage()));
            case RETRIES_EXHAUSTED, BUDGET_EXHAUSTED ->
                    retryOrFail(analysis, workerId, code, failure.getMessage());
        }
    }

    /**
     * 남은 호출 수가 임계 이하인지.
     *
     * <p>한도를 상수로 박지 않고 응답 헤더에 기록된 값을 쓴다. installation 토큰의 한도는
     * 저장소 수와 조직 인원에 따라 달라져서, 5,000을 가정하면 큰 조직에서 일찍 멈추고 작은
     * 조직에서는 늦게 멈춘다.
     */
    private OffsetDateTime nearRateLimitRetryAt(String token) {
        RateLimitSnapshot snapshot = rateLimitRecorder.latest(token, CORE_RESOURCE);
        if (snapshot == null || snapshot.remaining() == null
                || snapshot.remaining() > RATE_LIMIT_THRESHOLD) {
            return null;
        }
        OffsetDateTime fallback = OffsetDateTime.now().plusSeconds(
                Math.max(60L, properties.worker().pollInterval().toSeconds()));
        if (snapshot.resetAt() == null) {
            return fallback;
        }
        OffsetDateTime resetAt = OffsetDateTime.ofInstant(snapshot.resetAt(), ZoneOffset.UTC)
                .plusSeconds(1);
        return resetAt.isAfter(fallback) ? resetAt : fallback;
    }

    private void deferAll(List<PullRequestAnalysis> analyses, String workerId,
                          OffsetDateTime retryAt) {
        analyses.forEach(analysis ->
                writer.deferForRateLimit(analysis.getId(), workerId, retryAt));
    }

    private void retryAll(List<PullRequestAnalysis> analyses, String workerId,
                          SummaryFailureCode code, String message) {
        analyses.forEach(analysis -> retryOrFail(analysis, workerId, code, message));
    }

    private void failAll(List<PullRequestAnalysis> analyses, String workerId,
                         SummaryFailureCode code, String message) {
        analyses.forEach(analysis ->
                writer.fail(analysis.getId(), workerId, code, message));
    }

    private static OffsetDateTime retryAt(GithubRateLimitedException exception) {
        return OffsetDateTime.now().plusSeconds(exception.getRetryAfterSeconds());
    }

    private OffsetDateTime retryAt(int attempts) {
        int shift = Math.min(Math.max(0, attempts - 1), 20);
        long baseMillis = properties.worker().retryBackoff().toMillis();
        long multiplier = 1L << shift;
        long delayMillis;
        try {
            delayMillis = Math.multiplyExact(baseMillis, multiplier);
        } catch (ArithmeticException e) {
            delayMillis = Long.MAX_VALUE;
        }
        return OffsetDateTime.now().plusNanos(
                Math.min(delayMillis, 86_400_000L) * 1_000_000L);
    }

    private static void rememberLatest(AtomicReference<OffsetDateTime> target,
                                       OffsetDateTime candidate) {
        target.accumulateAndGet(candidate, (current, next) ->
                current == null || next.isAfter(current) ? next : current);
    }

    /**
     * 예산 소진과 그 밖의 실패를 나눈다.
     *
     * <p>사용자에게는 둘 다 "분석하지 못했다"이지만, 운영에서는 다르다. 시간 초과가 몰리면
     * 예산이나 모델을 손봐야 하고, 그 밖의 실패가 몰리면 프롬프트나 입력을 봐야 한다.
     */
    private static SummaryFailureCode failureCodeOf(PullRequestSummaryAiException e) {
        return e.getKind() == AiFailureKind.BUDGET_EXHAUSTED
                ? SummaryFailureCode.SUMMARY_LLM_TIMEOUT
                : SummaryFailureCode.SUMMARY_LLM_FAILED;
    }

    /**
     * 스키마에 없는 값이 오면 실패로 본다.
     *
     * <p>{@code OTHER}로 접지 않는다. {@code OTHER}는 "어디에도 안 맞는 변경"이라는 뜻이지
     * "모델이 규칙을 어겼다"는 뜻이 아니고, 둘을 섞으면 프롬프트가 무너진 것을 알아챌 방법이
     * 없어진다.
     */
    private static ChangeType toChangeType(String value) {
        try {
            return ChangeType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PullRequestSummaryInvalidResponseException(
                    "알 수 없는 변경 유형입니다. changeType: " + LogSafe.text(value));
        }
    }

    /** {@code installation_id}가 같은 요약을 한 묶음으로 만든다. 토큰을 그 단위로 발급한다. */
    private static Map<Long, List<PullRequestAnalysis>> groupByInstallation(
            List<PullRequestAnalysis> analyses) {
        Map<Long, List<PullRequestAnalysis>> grouped = new LinkedHashMap<>();
        for (PullRequestAnalysis analysis : analyses) {
            grouped.computeIfAbsent(analysis.getInstallationId(), key -> new ArrayList<>())
                    .add(analysis);
        }
        return grouped;
    }

    /** 병렬 task들이 한 installation의 현재 토큰과 단 한 번의 재발급 결과를 공유한다. */
    private final class InstallationToken {

        private final Long installationId;
        private final List<Long> repositoryIds;
        private String current;
        private boolean refreshed;
        private RuntimeException refreshFailure;

        private InstallationToken(Long installationId, List<Long> repositoryIds, String current) {
            this.installationId = installationId;
            this.repositoryIds = repositoryIds;
            this.current = current;
        }

        private synchronized String current() {
            return current;
        }

        private synchronized String refreshAfterRejection(String rejected) {
            if (!current.equals(rejected)) {
                return current;
            }
            if (refreshFailure != null) {
                // 동시 task들이 같은 재발급 실패를 서로 다른 종류로 해석하지 않게 한다.
                // 예를 들어 첫 task가 5xx를 맞았다면 나머지도 모두 일시 실패로 재시도해야 한다.
                throw refreshFailure;
            }
            if (refreshed) {
                throw new GithubInstallationUnavailableException();
            }
            refreshed = true;
            try {
                tokenService.invalidate(installationId, repositoryIds);
                current = tokenService.issue(installationId, repositoryIds);
                return current;
            } catch (RuntimeException e) {
                refreshFailure = e;
                throw e;
            }
        }
    }
}

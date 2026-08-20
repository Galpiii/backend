package com.github.galpiii.galpi.domain.analysis.worker;

import com.github.galpiii.galpi.domain.analysis.config.AnalysisWorkerProperties;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisConfig;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRun;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunTarget;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunTargetStatus;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisConfigRepository;
import com.github.galpiii.galpi.domain.analysis.repository.AnalysisRunTargetRepository;
import com.github.galpiii.galpi.domain.analysis.service.AnalysisRunWriter;
import com.github.galpiii.galpi.domain.collection.RepositoryCollector;
import com.github.galpiii.galpi.domain.collection.RepositoryCollector.RepositoryCollectionResult;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import com.github.galpiii.galpi.domain.github.client.RateLimitRecorder;
import com.github.galpiii.galpi.domain.github.client.RateLimitSnapshot;
import com.github.galpiii.galpi.domain.github.client.dto.GithubRepositoryResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.entity.GithubRepository;
import com.github.galpiii.galpi.domain.github.exception.GithubInstallationUnavailableException;
import com.github.galpiii.galpi.domain.github.exception.GithubRateLimitedException;
import com.github.galpiii.galpi.domain.github.exception.GithubRepositoryUnavailableException;
import com.github.galpiii.galpi.domain.github.service.GithubInstallationTokenService;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.util.LogSafe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 선점한 작업 하나를 끝까지 처리한다.
 *
 * <p>사용자 세션이 없는 자리다. {@code installation_snapshot}에 고정된 매핑만 보고 동작하며,
 * user access token을 요구하지 않는다. 이 구조 덕분에 "user token은 세션 동안만"이라는 원칙과
 * 몇 분씩 걸리는 분석이 양립한다.
 *
 * <p>{@code installation_id}별로 묶어 토큰을 한 번씩만 발급한다. 저장소마다 발급하면 같은
 * 설치에 대해 같은 토큰을 여러 번 만들게 되고, 캐시 키도 저장소마다 갈라져 캐시가 무의미해진다.
 *
 * <p>rate limit에 걸리면 <b>기다리지 않고</b> 중단한다. 남은 저장소는 SKIPPED로 두고 재개
 * 시각을 표시한 뒤 사용자가 다시 누르게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisRunExecutor {

    /** GitHub이 REST 호출에 붙이는 rate limit 버킷 이름. */
    private static final String CORE_RESOURCE = "core";

    private final AnalysisRunTargetRepository targetRepository;
    private final AnalysisConfigRepository configRepository;
    private final GithubInstallationTokenService tokenService;
    private final RepositoryCollector repositoryCollector;
    private final AnalysisRunWriter writer;
    private final RateLimitRecorder rateLimitRecorder;
    private final AnalysisWorkerProperties properties;

    public void execute(Long runId) {
        AnalysisRun run = writer.requireRun(runId);
        if (writer.isAbandoned(runId)) {
            // 큐에 들어간 뒤 프로젝트가 지워졌다. 상태는 이미 CANCELLED다.
            log.info("[분석] 취소된 작업이라 시작하지 않는다 runId={}", runId);
            return;
        }
        if (run.getAttempts() > properties.maxAttempts()) {
            log.warn("[분석] 시도 상한({})을 넘어 실패로 끝낸다 runId={}",
                    properties.maxAttempts(), runId);
            writer.failRun(runId, ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                    "시도 상한을 초과했습니다.");
            return;
        }

        List<AnalysisRunTarget> allTargets =
                targetRepository.findAllWithRepositoryByAnalysisRunId(runId);
        if (allTargets.isEmpty()) {
            writer.finishRun(runId, AnalysisRunStatus.COMPLETED);
            return;
        }

        Progress progress = Progress.from(allTargets);
        List<AnalysisRunTarget> targets = allTargets.stream()
                .filter(AnalysisRunExecutor::isProcessable)
                .toList();
        Map<Long, List<AnalysisRunTarget>> byInstallation = groupByInstallation(targets);

        for (Map.Entry<Long, List<AnalysisRunTarget>> group : byInstallation.entrySet()) {
            if (progress.abandoned) {
                break;
            }
            if (progress.rateLimited) {
                markRemainingSkipped(group.getValue(), progress);
                continue;
            }
            processGroup(run, group.getKey(), group.getValue(), progress);
        }

        finish(runId, progress);
    }

    private void processGroup(AnalysisRun run, Long installationId,
                              List<AnalysisRunTarget> targets, Progress progress) {
        List<Long> githubRepositoryIds = targets.stream()
                .map(target -> target.getRepository().getGithubRepositoryId())
                .toList();

        String token;
        try {
            token = tokenService.issue(installationId, githubRepositoryIds);
        } catch (GithubRateLimitedException e) {
            progress.rateLimited(e.getRetryAfterSeconds());
            markRemainingSkipped(targets, progress);
            return;
        } catch (GlobalException e) {
            // 설치가 사라졌거나 정지됐다. 이 설치의 저장소는 전부 실패지만 다른 설치는 계속한다.
            log.warn("[분석] installation token을 발급하지 못해 그룹 전체를 실패 처리한다 "
                    + "installationId={} code={}", installationId, e.getErrorCode().getCode());
            for (AnalysisRunTarget target : targets) {
                writer.markRepositoryInaccessible(target.getRepository().getId());
                writer.failTarget(target.getId(), e.getErrorCode().getCode(),
                        e.getErrorCode().getMessage());
                progress.failed++;
            }
            return;
        }

        boolean tokenRefreshed = false;
        int index = 0;
        while (index < targets.size()) {
            AnalysisRunTarget target = targets.get(index);
            // 저장소 사이가 체크포인트다. 프로젝트가 지워졌거나 작업이 취소됐으면 남은
            // 저장소는 시작하지 않는다. 저장소 하나가 몇 분씩 걸려 여기서 보지 않으면
            // 삭제 직후에도 프로젝트 전체를 끝까지 수집한다.
            if (writer.isAbandoned(run.getId())) {
                log.info("[분석] 취소·삭제를 확인해 남은 저장소를 중단한다 runId={} remaining={}",
                        run.getId(), targets.size() - index);
                progress.abandoned = true;
                return;
            }
            if (progress.rateLimited) {
                markSkipped(target, progress);
                index++;
                continue;
            }
            // 403을 맞고 나서 멈추면 절반쯤 수집한 저장소를 버리게 된다. 다음 저장소를
            // 시작하기 전에 남은 호출 수를 보고 미리 접는다.
            Optional<OffsetDateTime> resumeAt = nearRateLimit(token);
            if (resumeAt.isPresent()) {
                log.warn("[분석] 남은 호출 수가 임계({}) 이하라 수집을 중단한다 runId={}",
                        properties.rateLimitThreshold(), run.getId());
                progress.rateLimited(resumeAt.get());
                markSkipped(target, progress);
                index++;
                continue;
            }
            try {
                collectOne(run, target, token, installationId, progress);
                index++;
            } catch (GithubInstallationUnavailableException e) {
                if (tokenRefreshed) {
                    failInstallationTargets(targets.subList(index, targets.size()), e, progress);
                    return;
                }

                // 캐시된 토큰이 수집 도중 만료·폐기됐을 수 있다. 정확히 한 번만 새로 발급해
                // 같은 저장소부터 재시도한다. 계속 401이면 설치 자체의 문제로 확정한다.
                tokenRefreshed = true;
                tokenService.invalidate(installationId, githubRepositoryIds);
                try {
                    token = tokenService.issue(installationId, githubRepositoryIds);
                } catch (GithubRateLimitedException rateLimited) {
                    progress.rateLimited(rateLimited.getRetryAfterSeconds());
                    markRemainingSkipped(targets.subList(index, targets.size()), progress);
                    return;
                } catch (GlobalException unavailable) {
                    failInstallationTargets(targets.subList(index, targets.size()),
                            unavailable, progress);
                    return;
                }
            }
        }
    }

    /**
     * 남은 호출 수가 임계 이하인지 본다.
     *
     * <p>한도를 상수로 박지 않고 응답 헤더에 기록된 값을 쓴다. installation 토큰의 한도는
     * 저장소 수와 조직 인원에 따라 달라져서, 5,000을 가정하면 큰 조직에서 일찍 멈추고
     * 작은 조직에서는 늦게 멈춘다.
     *
     * <p>토큰의 원문은 저장하지 않고 해시를 키로 삼는다. 사용자 OAuth 호출이나 다른
     * installation의 응답이 현재 작업의 중단 판단을 오염시키지 않는다.
     */
    private Optional<OffsetDateTime> nearRateLimit(String token) {
        RateLimitSnapshot snapshot = rateLimitRecorder.latest(token, CORE_RESOURCE);
        if (snapshot == null || snapshot.remaining() == null
                || snapshot.remaining() > properties.rateLimitThreshold()) {
            return Optional.empty();
        }
        return Optional.of(snapshot.resetAt() == null
                ? OffsetDateTime.now().plusMinutes(1)
                : OffsetDateTime.ofInstant(snapshot.resetAt(), ZoneOffset.UTC));
    }

    private void collectOne(AnalysisRun run, AnalysisRunTarget target, String token,
                            Long installationId, Progress progress) {
        GithubRepository repository = target.getRepository();
        writer.startTarget(target.getId());

        try {
            AnalysisConfig config = resolveConfig(run.getProject().getId(), repository.getId());
            RepositoryCollectionResult result = repositoryCollector.collect(
                    new RepositoryCollector.CollectionRequest(
                            token,
                            repository.getOwner(),
                            repository.getName(),
                            repository.getId(),
                            repository.getGithubRepositoryId(),
                            config == null ? AnalysisConfig.DEFAULT_PR_LIMIT : config.getPrLimit(),
                            config == null ? null : config.getPrSince(),
                            config == null ? List.of() : config.getIncludePaths(),
                            config == null ? List.of() : config.getExcludePaths()));

            writer.refreshRepositorySnapshot(repository.getId(),
                    toSnapshot(result.repository(), installationId));
            writer.completeTarget(target.getId(), result);
            progress.completed++;
        } catch (GithubRateLimitedException e) {
            // 여기서 자지 않는다. 작업을 멈추고 사용자가 재시도한다.
            log.warn("[분석] rate limit으로 수집을 중단한다 runId={} repositoryId={} retryAfter={}s",
                    run.getId(), repository.getId(), e.getRetryAfterSeconds());
            progress.rateLimited(e.getRetryAfterSeconds());
            markSkipped(target, progress);
        } catch (GithubRepositoryUnavailableException e) {
            // 저장소 하나가 404여도 나머지는 계속 간다.
            writer.markRepositoryInaccessible(repository.getId());
            failTarget(target, e, progress);
        } catch (GithubInstallationUnavailableException e) {
            throw e;
        } catch (GlobalException e) {
            failTarget(target, e, progress);
        } catch (RuntimeException e) {
            log.warn("[분석] 저장소 수집이 예상치 못한 이유로 실패했다 repositoryId={} cause={}",
                    repository.getId(), e.getClass().getSimpleName());
            writer.failTarget(target.getId(), ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                    e.getClass().getSimpleName());
            progress.failed++;
        }
    }

    private void failTarget(AnalysisRunTarget target, GlobalException e, Progress progress) {
        log.info("[분석] 저장소 하나를 실패로 기록한다 repositoryId={} code={}",
                target.getRepository().getId(), e.getErrorCode().getCode());
        writer.failTarget(target.getId(), e.getErrorCode().getCode(),
                LogSafe.text(e.getErrorCode().getMessage()));
        progress.failed++;
    }

    private void failInstallationTargets(List<AnalysisRunTarget> targets, GlobalException e,
                                         Progress progress) {
        log.warn("[분석] installation token 재발급 후에도 접근할 수 없어 그룹을 실패 처리한다 "
                + "repositoryCount={} code={}", targets.size(), e.getErrorCode().getCode());
        for (AnalysisRunTarget target : targets) {
            writer.markRepositoryInaccessible(target.getRepository().getId());
            failTarget(target, e, progress);
        }
    }

    /**
     * 전체 상태를 확정한다.
     *
     * <p>하나라도 실패했지만 하나라도 성공했으면 {@code PARTIALLY_COMPLETED}다. 부분 실패를
     * 전체 실패로 만들지 않는다는 원칙이 여기서 값으로 나타난다.
     */
    private void finish(Long runId, Progress progress) {
        if (progress.abandoned) {
            // CANCELLED를 결과 상태로 덮지 않는다. 이미 끝난 저장소의 기록은 그대로 남는다.
            return;
        }
        if (progress.rateLimited) {
            writer.rateLimitRun(runId, progress.resumeAt);
            return;
        }
        if (progress.completed == 0 && progress.failed > 0) {
            writer.finishRun(runId, AnalysisRunStatus.FAILED);
            return;
        }
        writer.finishRun(runId, progress.failed > 0 || progress.skipped > 0
                ? AnalysisRunStatus.PARTIALLY_COMPLETED
                : AnalysisRunStatus.COMPLETED);
    }

    /** 저장소별 설정이 있으면 그것, 없으면 프로젝트 기본값, 그것도 없으면 {@code null}. */
    private AnalysisConfig resolveConfig(Long projectId, Long repositoryId) {
        return configRepository.findByProjectIdAndRepositoryId(projectId, repositoryId)
                .or(() -> configRepository.findByProjectIdAndRepositoryIsNull(projectId))
                .orElse(null);
    }

    private void markRemainingSkipped(List<AnalysisRunTarget> targets, Progress progress) {
        targets.forEach(target -> markSkipped(target, progress));
    }

    private void markSkipped(AnalysisRunTarget target, Progress progress) {
        writer.skipTarget(target.getId(), List.of(IncompleteReason.RATE_LIMITED));
        progress.skipped++;
    }

    /** {@code installation_id}가 같은 저장소를 한 묶음으로 만든다. 토큰을 그 단위로 발급한다. */
    private static Map<Long, List<AnalysisRunTarget>> groupByInstallation(
            List<AnalysisRunTarget> targets) {
        Map<Long, List<AnalysisRunTarget>> grouped = new LinkedHashMap<>();
        for (AnalysisRunTarget target : targets) {
            grouped.computeIfAbsent(target.getInstallationId(), key -> new ArrayList<>())
                    .add(target);
        }
        return grouped;
    }

    private static boolean isProcessable(AnalysisRunTarget target) {
        return target.getStatus() == AnalysisRunTargetStatus.PENDING
                || target.getStatus() == AnalysisRunTargetStatus.COLLECTING;
    }

    private static RepositorySnapshot toSnapshot(GithubRepositoryResponse repository,
                                                 Long installationId) {
        return new RepositorySnapshot(
                repository.id(),
                installationId,
                repository.ownerLogin(),
                repository.name(),
                repository.fullName(),
                Boolean.TRUE.equals(repository.isPrivate()),
                repository.defaultBranch(),
                repository.htmlUrl());
    }

    /** 저장소별 결과를 세어 전체 상태를 정하기 위한 누적값. */
    private static final class Progress {
        private int completed;
        private int failed;
        private int skipped;
        private boolean rateLimited;
        /** 프로젝트 삭제나 취소로 더 진행할 이유가 없어졌다. */
        private boolean abandoned;
        private OffsetDateTime resumeAt;

        private static Progress from(List<AnalysisRunTarget> targets) {
            Progress progress = new Progress();
            for (AnalysisRunTarget target : targets) {
                switch (target.getStatus()) {
                    case COMPLETED -> progress.completed++;
                    case FAILED -> progress.failed++;
                    case SKIPPED -> progress.skipped++;
                    case PENDING, COLLECTING -> {
                        // 이번 실행이나 stale lease 복구에서 처리한다.
                    }
                }
            }
            return progress;
        }

        private void rateLimited(long retryAfterSeconds) {
            rateLimited(OffsetDateTime.now().plusSeconds(retryAfterSeconds));
        }

        private void rateLimited(OffsetDateTime resumeAt) {
            this.rateLimited = true;
            this.resumeAt = resumeAt;
        }
    }
}

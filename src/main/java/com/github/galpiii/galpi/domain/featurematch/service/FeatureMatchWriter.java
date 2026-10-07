package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.ai.dto.FeatureMatchingRequest;
import com.github.galpiii.galpi.ai.dto.FeatureMatchingResult;
import com.github.galpiii.galpi.domain.collection.repository.PullRequestRepository;
import com.github.galpiii.galpi.domain.consent.service.AiDataConsentService;
import com.github.galpiii.galpi.domain.featurematch.config.FeatureMatchProperties;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.Counts;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.ProjectRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.PrRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RequirementRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RunRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.MatchRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.TargetRow;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentFeature;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentPullRequest;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentState;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchFailureCode;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRun;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunType;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchSource;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchTargetStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeaturePrMatch;
import com.github.galpiii.galpi.domain.featurematch.entity.FeaturePrMatchRequirement;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentFeatureRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentPullRequestRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentStateRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchRunRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchTargetRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeaturePrMatchRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeaturePrMatchRequirementRepository;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchInputAssembler;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchResultValidator;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchSnapshot;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.FeatureRequirementRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** AI 네트워크 호출 전후에 짧은 트랜잭션을 분리하고 선점·권한·동의를 재검증한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureMatchWriter {

    private final FeatureMatchQueryRepository queryRepository;
    private final FeatureMatchInputAssembler assembler;
    private final FeatureMatchResultValidator validator;
    private final FeatureMatchProperties properties;
    private final AiDataConsentService consent;
    private final FeatureMatchRunRepository runRepository;
    private final FeatureMatchTargetRepository targetRepository;
    private final FeaturePrMatchRepository matchRepository;
    private final FeaturePrMatchRequirementRepository requirementMatchRepository;
    private final FeatureRepository featureRepository;
    private final FeatureRequirementRepository requirementRepository;
    private final PullRequestRepository pullRequestRepository;
    private final SpecDocumentRepository documentRepository;
    private final ProjectRepository projectRepository;
    private final FeatureMatchCurrentStateRepository currentStateRepository;
    private final FeatureMatchCurrentFeatureRepository currentFeatureRepository;
    private final FeatureMatchCurrentPullRequestRepository currentPrRepository;

    public record Prepared(String input) {
    }

    private record Context(RunRow run, TargetRow target, List<FeatureRow> features,
                           List<RequirementRow> requirements,
                           PrRow pr) {
    }

    @Transactional
    public Prepared prepare(long id, String token) {
        Context context = context(id, token);
        if (context == null) {
            return null;
        }
        if (context.target().attempts() > properties.maxAttempts()) {
            log.warn("[기능대조] 최대 시도 횟수 초과 runId={} targetId={} attempts={}",
                    context.run().id(), id, context.target().attempts());
            finishTarget(id, token, FeatureMatchTargetStatus.FAILED, FeatureMatchFailureCode.MAX_ATTEMPTS_EXCEEDED, null);
            aggregate(context.run().id());
            return null;
        }
        runRepository.findById(context.run().id()).ifPresent(FeatureMatchRun::start);
        return new Prepared(context.target().inputJson() == null
                ? assembler.assemble(context.features(), context.requirements(), context.pr())
                : context.target().inputJson());
    }

    @Transactional
    public void complete(long id, String token, FeatureMatchingResult result) {
        Context context = context(id, token);
        if (context == null) {
            return;
        }
        FeatureMatchingRequest input = context.target().inputJson() == null ? null
                : FeatureMatchSnapshot.parseRequest(context.target().inputJson());
        result = input == null ? validator.validate(result, context.features(), context.requirements())
                : validator.validate(result, input);
        List<MatchRow> existing = queryRepository.matches(context.run().specDocumentId());
        matchRepository.deleteAiByProjectAndPullRequestIds(context.run().projectId(), List.of(context.pr().id()));
        for (FeatureMatchingResult.Match match : result.matches()) {
            if (existing.stream().anyMatch(m -> m.source() == FeatureMatchSource.USER
                    && m.featureId() == match.featureId() && m.pullRequestId() == context.pr().id())) {
                continue;
            }
            if (!featureRepository.existsById(match.featureId())) {
                continue;
            }
            FeaturePrMatch saved = matchRepository.save(FeaturePrMatch.currentAi(
                    featureRepository.getReferenceById(match.featureId()),
                    pullRequestRepository.getReferenceById(context.pr().id()), assembler.safe(match.reason(), null)));
            requirementMatchRepository.saveAll(match.requirementIds().stream()
                    .filter(requirementRepository::existsById)
                    .map(requirementId -> FeaturePrMatchRequirement.of(saved,
                            requirementRepository.getReferenceById(requirementId)))
                    .toList());
        }
        FeatureMatchSnapshot.PullRequestInput snapshot = context.target().inputJson() == null ? null
                : FeatureMatchSnapshot.pullRequestInput(context.target().inputJson());
        currentPrRepository.findById(context.pr().id()).ifPresentOrElse(
                current -> {
                    if (snapshot == null) {
                        current.update(context.target().analysisSnapshotHash(), context.target().sourceSnapshotHash());
                    } else {
                        current.update(snapshot.analysisHash(), snapshot.sourceHash(),
                                snapshot.sectionChars(), snapshot.analysisChars());
                    }
                },
                () -> currentPrRepository.save(snapshot == null
                        ? new FeatureMatchCurrentPullRequest(context.run().projectId(), context.pr().id(),
                        context.target().analysisSnapshotHash(), context.target().sourceSnapshotHash())
                        : new FeatureMatchCurrentPullRequest(context.run().projectId(), context.pr().id(),
                        snapshot.analysisHash(), snapshot.sourceHash(), snapshot.sectionChars(), snapshot.analysisChars())));
        finishTarget(id, token, FeatureMatchTargetStatus.COMPLETED, null, null);
        aggregate(context.run().id());
    }

    @Transactional
    public void fail(long id, String token, FeatureMatchFailureCode code, boolean retryable) {
        TargetRow initial = queryRepository.target(id);
        if (initial == null) {
            return;
        }
        queryRepository.lockRun(initial.featureMatchRunId());
        queryRepository.lockTarget(id);
        TargetRow target = queryRepository.target(id);
        if (!owned(target, token)) {
            return;
        }
        boolean retry = retryable && target.attempts() < properties.maxAttempts();
        OffsetDateTime next = retry ? OffsetDateTime.now().plus(properties.retryBackoff()
                .multipliedBy(1L << Math.min(target.attempts() - 1, 10))) : null;
        finishTarget(id, token, retry ? FeatureMatchTargetStatus.PENDING : FeatureMatchTargetStatus.FAILED, code, next);
        log.info("[기능대조] 대상 처리 실패 runId={} targetId={} code={} retry={} attempts={} nextAttemptAt={}",
                target.featureMatchRunId(), id, code, retry, target.attempts(), next);
        aggregate(target.featureMatchRunId());
    }

    /**
     * 모든 저장/전송 직전에 권한·동의·선점 토큰을 다시 확인한다.
     */
    private Context context(long id, String token) {
        TargetRow initial = queryRepository.target(id);
        if (initial == null) {
            return null;
        }
        RunRow run = queryRepository.run(initial.featureMatchRunId());
        if (run == null) {
            return null;
        }
        ProjectRow project = queryRepository.project(run.projectId(), run.userId(), true);
        if (project != null) {
            documentRepository.lockById(run.specDocumentId());
        }
        queryRepository.lockRun(run.id());
        queryRepository.lockTarget(id);
        TargetRow target = queryRepository.target(id);
        if (!owned(target, token)) {
            return null;
        }
        run = queryRepository.run(run.id());
        if (run == null || (run.status() != FeatureMatchRunStatus.QUEUED && run.status() != FeatureMatchRunStatus.RUNNING)) {
            return null;
        }
        if (project == null) {
            return cancel(run, FeatureMatchFailureCode.PROJECT_DELETED);
        }
        if (!queryRepository.connected(run.userId())) {
            return cancel(run, FeatureMatchFailureCode.GITHUB_DISCONNECTED);
        }
        if (!consent.status(run.userId()).agreed()) {
            return cancel(run, FeatureMatchFailureCode.CONSENT_REVOKED);
        }
        List<FeatureRow> features = queryRepository.features(run.specDocumentId());
        List<RequirementRow> requirements = queryRepository.requirements(run.specDocumentId());
        PrRow pr = queryRepository.pullRequestForAnalysis(
                target.pullRequestAnalysisId(), run.projectId());
        if (pr == null) {
            finishTarget(id, token, FeatureMatchTargetStatus.CANCELLED, FeatureMatchFailureCode.SOURCE_CHANGED, null);
            aggregate(run.id());
            return null;
        }
        return new Context(run, target, features, requirements, pr);
    }

    private Context cancel(RunRow run, FeatureMatchFailureCode reason) {
        log.info("[기능대조] 실행 취소 projectId={} runId={} reason={}", run.projectId(), run.id(), reason);
        targetRepository.findAllByFeatureMatchRunId(run.id()).forEach(target -> target.cancel(reason));
        int completed = queryRepository.counts(run.id()).completedCount();
        runRepository.findById(run.id()).ifPresent(entity -> {
            entity.finish(completed > 0 ? FeatureMatchRunStatus.PARTIALLY_COMPLETED
                    : FeatureMatchRunStatus.CANCELLED, reason);
            if (completed > 0 && entity.getRunType() == FeatureMatchRunType.FULL
                    && reason != FeatureMatchFailureCode.PROJECT_DELETED) {
                publishFull(entity, false);
            }
        });
        return null;
    }

    private static boolean owned(TargetRow target, String token) {
        return target != null && target.status() == FeatureMatchTargetStatus.RUNNING && Objects.equals(target.claimedBy(), token);
    }

    private void aggregate(long runId) {
        // 같은 실행의 완료 처리는 run 행 잠금으로 직렬화한다. 마지막 두 워커가 모두
        // 상대를 RUNNING으로 보고 실행을 영원히 남겨 두는 것을 막는다.
        Counts context = queryRepository.counts(runId);
        if (context.pendingCount() + context.runningCount() > 0) {
            return;
        }
        FeatureMatchRunStatus status = context.completedCount() > 0
                ? (context.failedCount() + context.cancelledCount() == 0
                ? FeatureMatchRunStatus.COMPLETED : FeatureMatchRunStatus.PARTIALLY_COMPLETED)
                : (context.failedCount() > 0 ? FeatureMatchRunStatus.FAILED : FeatureMatchRunStatus.CANCELLED);
        FeatureMatchFailureCode failureCode = targetRepository.countFailures(runId).stream()
                .findFirst()
                .map(FeatureMatchTargetRepository.FailureCodeCount::getFailureCode)
                .orElse(null);
        runRepository.findById(runId).ifPresent(run -> {
            if (run.isInFlight()) {
                run.finish(status, failureCode);
                if (run.getRunType() == FeatureMatchRunType.FULL && context.completedCount() > 0) {
                    publishFull(run, context.failedCount() + context.cancelledCount() == 0);
                }
                log.info("[기능대조] 실행 종료 runId={} status={} failureCode={}", runId, status, failureCode);
            }
        });
    }

    private void publishFull(FeatureMatchRun run, boolean complete) {
        long projectId = run.getProject().getId();
        Set<Long> linkedPrIds = queryRepository.pullRequests(projectId).stream()
                .map(PrRow::id).collect(Collectors.toSet());
        List<Long> removedPrIds = currentPrRepository.findAllByProjectId(projectId).stream()
                .map(FeatureMatchCurrentPullRequest::getPullRequestId)
                .filter(id -> !linkedPrIds.contains(id)).toList();
        if (!removedPrIds.isEmpty()) {
            matchRepository.deleteAiByProjectAndPullRequestIds(projectId, removedPrIds);
            currentPrRepository.deleteAllById(removedPrIds);
        }
        FeatureMatchCurrentState current = currentStateRepository.findById(run.getProject().getId()).orElse(null);
        if (current == null) {
            current = currentStateRepository.save(new FeatureMatchCurrentState(
                    projectRepository.getReferenceById(run.getProject().getId()), run.getSpecDocument(),
                    run, run.getFeatureSnapshotHash()));
            complete = true;
        } else {
            current.update(run.getSpecDocument(), run,
                    complete ? run.getFeatureSnapshotHash() : current.getFeatureSnapshotHash());
        }
        currentStateRepository.flush();
        if (complete && run.getFeatureSnapshotJson() != null) {
            currentFeatureRepository.deleteAllByProjectId(run.getProject().getId());
            currentFeatureRepository.flush();
            Map<Long, String> hashes = FeatureMatchSnapshot.parseFeatureHashes(run.getFeatureSnapshotJson());
            currentFeatureRepository.saveAll(hashes.entrySet().stream()
                    .map(entry -> new FeatureMatchCurrentFeature(run.getProject().getId(),
                            entry.getKey(), entry.getValue())).toList());
        }
        runRepository.deleteAll(runRepository.findAllByProjectIdAndIdNot(run.getProject().getId(), run.getId()));
    }

    private void finishTarget(long id, String token, FeatureMatchTargetStatus status,
                              FeatureMatchFailureCode code, OffsetDateTime nextAttemptAt) {
        targetRepository.findById(id).ifPresent(target -> target.finish(token, status, code, nextAttemptAt));
    }

    /**
     * 처리할 대상이 사라진 실행만 복구한다. 프로젝트·문서를 잠그거나 원문 해시를 다시 읽지 않는다.
     * 호출 한 번이 실행 한 건의 트랜잭션이다.
     */
    @Transactional
    public void reconcile(long runId) {
        queryRepository.lockRun(runId);
        RunRow run = queryRepository.run(runId);
        if (run == null || (run.status() != FeatureMatchRunStatus.QUEUED
                && run.status() != FeatureMatchRunStatus.RUNNING)) {
            return;
        }
        if (queryRepository.targetCount(runId) != run.eligiblePrCount()) {
            cancel(run, FeatureMatchFailureCode.SOURCE_CHANGED);
            return;
        }
        aggregate(runId);
    }
}

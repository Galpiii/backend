package com.github.galpiii.galpi.domain.featurematch.service;

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
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.TargetRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.MatchRow;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchFailureCode;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRun;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchTargetStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeaturePrMatch;
import com.github.galpiii.galpi.domain.featurematch.entity.FeaturePrMatchRequirement;
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
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * AI 네트워크 호출 전후에 짧은 트랜잭션을 분리하고 저장 직전 실행 기준을 재검증한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureMatchWriter {

    private final FeatureMatchQueryRepository queryRepository;
    private final FeatureMatchScope scope;
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
        return new Prepared(assembler.assemble(context.features(), context.requirements(), context.pr()));
    }

    @Transactional
    public void complete(long id, String token, FeatureMatchingResult result) {
        Context context = context(id, token);
        if (context == null) {
            return;
        }
        result = validator.validate(result, context.features(), context.requirements());
        List<MatchRow> existing = queryRepository.matches(context.run().id(), context.run().specDocumentId());
        for (FeatureMatchingResult.Match match : result.matches()) {
            if (existing.stream().anyMatch(m -> m.featureId() == match.featureId() && m.pullRequestId() == context.pr().id())) {
                continue;
            } // 이전 실행에서 사용자가 직접 연결한 쌍은 AI가 덮어쓰지 않는다.
            FeaturePrMatch saved = matchRepository.save(FeaturePrMatch.byAi(
                    runRepository.getReferenceById(context.run().id()), targetRepository.getReferenceById(id),
                    featureRepository.getReferenceById(match.featureId()),
                    pullRequestRepository.getReferenceById(context.pr().id()), assembler.safe(match.reason(), null)));
            requirementMatchRepository.saveAll(match.requirementIds().stream()
                    .map(requirementId -> FeaturePrMatchRequirement.of(saved,
                            requirementRepository.getReferenceById(requirementId)))
                    .toList());
        }
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
     * 모든 저장/전송 직전에 권한·동의·스냅샷·선점 토큰을 다시 확인한다.
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
        if (!Objects.equals(project.activeSpecDocumentId(), run.specDocumentId())) {
            return cancel(run, FeatureMatchFailureCode.SOURCE_CHANGED);
        }
        List<FeatureRow> features = queryRepository.features(run.specDocumentId());
        List<RequirementRow> requirements = queryRepository.requirements(run.specDocumentId());
        if (!run.featureSnapshotHash().equals(FeatureMatchSnapshot.featureHash(features, requirements))
                || queryRepository.targetCount(run.id()) != run.eligiblePrCount()) {
            return cancel(run, FeatureMatchFailureCode.SOURCE_CHANGED);
        }
        // 요약 완료와 결과 저장이 같은 트랜잭션에서 경쟁하지 않도록 요약 행도 잠근다.
        queryRepository.lockAnalysis(target.pullRequestAnalysisId());
        PrRow pr = queryRepository.pullRequestForAnalysis(
                target.pullRequestAnalysisId(), run.projectId());
        if (!scope.current(target, pr)) {
            return cancel(run, FeatureMatchFailureCode.SOURCE_CHANGED);
        }
        return new Context(run, target, features, requirements, pr);
    }

    private Context cancel(RunRow run, FeatureMatchFailureCode reason) {
        log.info("[기능대조] 실행 취소 projectId={} runId={} reason={}", run.projectId(), run.id(), reason);
        targetRepository.findAllByFeatureMatchRunId(run.id()).forEach(target -> target.cancel(reason));
        runRepository.findById(run.id()).ifPresent(entity -> entity.finish(FeatureMatchRunStatus.CANCELLED, reason));
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
                log.info("[기능대조] 실행 종료 runId={} status={} failureCode={}", runId, status, failureCode);
            }
        });
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

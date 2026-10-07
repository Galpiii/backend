package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.PrRow;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchChangesResponse;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentFeature;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentPullRequest;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentState;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchTarget;
import com.github.galpiii.galpi.domain.featurematch.exception.FeatureMatchInputTooLargeException;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentFeatureRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentPullRequestRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentStateRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchTargetRepository;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchInputAssembler;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchSnapshot;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FeatureMatchChangeService {
    private final FeatureMatchQueryRepository queryRepository;
    private final FeatureMatchCurrentStateRepository stateRepository;
    private final FeatureMatchCurrentFeatureRepository featureRepository;
    private final FeatureMatchCurrentPullRequestRepository pullRequestRepository;
    private final FeatureMatchTargetRepository targetRepository;
    private final FeatureMatchInputAssembler assembler;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfillLegacySnapshots() {
        for (FeatureMatchCurrentState item : stateRepository.findAll()) {
            FeatureMatchCurrentState state = stateRepository.lockByProjectId(item.getProjectId()).orElse(null);
            if (state == null) {
                continue;
            }
            List<FeatureRow> features = queryRepository.features(state.getSpecDocument().getId());
            var requirements = queryRepository.requirements(state.getSpecDocument().getId());
            if (featureRepository.findAllByProjectId(item.getProjectId()).isEmpty()
                    && Objects.equals(state.getFeatureSnapshotHash(),
                    FeatureMatchSnapshot.featureHash(features, requirements))) {
                featureRepository.saveAll(FeatureMatchSnapshot.featureHashes(features, requirements).entrySet().stream()
                        .map(entry -> new FeatureMatchCurrentFeature(item.getProjectId(), entry.getKey(), entry.getValue()))
                        .toList());
            }
            List<FeatureMatchCurrentPullRequest> savedPrs = pullRequestRepository.findAllByProjectId(item.getProjectId());
            Map<Long, FeatureMatchTarget> completedTargets = new HashMap<>();
            if (savedPrs.stream().anyMatch(saved -> saved.getFeatureInputChars() == null
                    || saved.getAnalysisInputChars() == null || saved.getSourceSnapshotHash() == null)) {
                targetRepository.completedByProject(item.getProjectId()).forEach(target ->
                        completedTargets.putIfAbsent(target.getPullRequestAnalysis().getPullRequest().getId(), target));
            }
            for (FeatureMatchCurrentPullRequest saved : savedPrs) {
                if (saved.getFeatureInputChars() != null && saved.getAnalysisInputChars() != null
                        && saved.getSourceSnapshotHash() != null) {
                    continue;
                }
                FeatureMatchTarget target = completedTargets.get(saved.getPullRequestId());
                if (target != null && target.getInputJson() != null
                        && Objects.equals(saved.getAnalysisSnapshotHash(), target.getAnalysisSnapshotHash())) {
                    var snapshot = FeatureMatchSnapshot.pullRequestInput(target.getInputJson());
                    saved.backfillInput(snapshot.analysisHash(), snapshot.sourceHash(),
                            snapshot.sectionChars(), snapshot.analysisChars());
                }
            }
        }
    }

    public record Changes(FeatureMatchCurrentState state, List<FeatureRow> features,
                          List<PrRow> pullRequests, List<PrRow> changedPullRequests,
                          List<Long> removedPullRequestIds, List<Long> removedFeatureIds,
                          List<FeatureRow> changedFeatures, boolean fullRequired,
                          String featureHash, boolean pullRequestChanged, boolean analysisBlocked,
                          boolean reanalysisRequired) {
        public boolean stale() {
            return fullRequired || pullRequestChanged || !removedPullRequestIds.isEmpty()
                    || !removedFeatureIds.isEmpty();
        }
    }

    public FeatureMatchChangesResponse get(long projectId, long userId) {
        Changes changes = changes(projectId, userId);
        List<PrRow> readyChanges = changes.analysisBlocked() ? List.of() : changes.changedPullRequests();
        return new FeatureMatchChangesResponse(
                changes.stale() ? FeatureMatchChangesResponse.Freshness.STALE
                        : FeatureMatchChangesResponse.Freshness.CURRENT,
                changes.fullRequired(),
                readyChanges.stream().map(pr -> new FeatureMatchChangesResponse.ChangedPullRequest(
                        pr.id(), pr.number(), pr.title())).toList(),
                changes.changedFeatures().stream().map(feature -> new FeatureMatchChangesResponse.ChangedFeature(
                        feature.id(), feature.name())).toList(),
                changes.removedPullRequestIds(), changes.removedFeatureIds(),
                readyChanges.size() + changes.removedPullRequestIds().size(),
                changes.changedFeatures().size() + changes.removedFeatureIds().size(),
                Stream.of(
                        !changes.pullRequestChanged() && changes.removedPullRequestIds().isEmpty()
                                ? null : FeatureMatchChangesResponse.StaleReason.PULL_REQUEST_CHANGED,
                        changes.fullRequired() || !changes.removedFeatureIds().isEmpty()
                                ? FeatureMatchChangesResponse.StaleReason.FEATURE_CHANGED : null)
                        .filter(Objects::nonNull).toList(),
                Stream.of(
                        changes.analysisBlocked()
                                ? FeatureMatchChangesResponse.RerunBlockReason.PR_ANALYSIS_NOT_READY : null,
                        changes.reanalysisRequired()
                                ? FeatureMatchChangesResponse.RerunBlockReason.PR_REANALYSIS_REQUIRED : null)
                        .filter(Objects::nonNull).toList());
    }

    public Changes changes(long projectId, long userId) {
        var project = queryRepository.project(projectId, userId, false);
        if (project == null) {
            throw new NotFoundException(ErrorCode.PROJECT_NOT_FOUND);
        }
        FeatureMatchCurrentState state = stateRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.FEATURE_MATCH_RESULT_NOT_FOUND));
        if (!Objects.equals(project.activeSpecDocumentId(), state.getSpecDocument().getId())) {
            throw new NotFoundException(ErrorCode.FEATURE_MATCH_RESULT_NOT_FOUND);
        }
        List<FeatureRow> features = queryRepository.features(state.getSpecDocument().getId());
        var requirements = queryRepository.requirements(state.getSpecDocument().getId());
        String featureHash = FeatureMatchSnapshot.featureHash(features, requirements);
        Map<Long, String> currentFeatures = FeatureMatchSnapshot.featureHashes(features, requirements);
        Map<Long, String> savedFeatures = featureRepository.findAllByProjectId(projectId).stream()
                .collect(Collectors.toMap(FeatureMatchCurrentFeature::getFeatureId,
                        FeatureMatchCurrentFeature::getSnapshotHash));
        // V17 결과를 마이그레이션한 프로젝트는 기능별 해시가 없다. 전체 해시가 같으면 변경이 없다.
        boolean legacy = savedFeatures.isEmpty() && Objects.equals(state.getFeatureSnapshotHash(), featureHash);
        List<FeatureRow> changedFeatures = savedFeatures.isEmpty() ? List.of() : features.stream()
                .filter(feature -> !Objects.equals(savedFeatures.get(feature.id()), currentFeatures.get(feature.id())))
                .toList();
        List<Long> removedFeatures = legacy ? List.of() : savedFeatures.keySet().stream()
                .filter(id -> !currentFeatures.containsKey(id)).sorted().toList();
        boolean fullRequired = !changedFeatures.isEmpty()
                || (!legacy && savedFeatures.isEmpty() && !Objects.equals(state.getFeatureSnapshotHash(), featureHash));

        List<PrRow> prs = queryRepository.pullRequests(projectId);
        Map<Long, FeatureMatchCurrentPullRequest> savedPrs = pullRequestRepository.findAllByProjectId(projectId).stream()
                .collect(Collectors.toMap(FeatureMatchCurrentPullRequest::getPullRequestId, pr -> pr));
        Map<Long, Boolean> sourceChanges = prs.stream()
                .collect(Collectors.toMap(PrRow::id, pr -> sourceChanged(pr, savedPrs.get(pr.id()))));
        List<PrRow> changedPrs = prs.stream()
                .filter(pr -> pr.analysisStatus() == PullRequestAnalysisStatus.COMPLETED
                        && Objects.equals(pr.headSha(), pr.analysisHeadSha()))
                .filter(pr -> sourceChanges.get(pr.id()))
                .toList();
        boolean pullRequestChanged = sourceChanges.containsValue(true);
        boolean reanalysisRequired = prs.stream().anyMatch(pr -> savedPrs.containsKey(pr.id())
                && (pr.analysisStatus() == PullRequestAnalysisStatus.FAILED
                || pr.analysisStatus() == PullRequestAnalysisStatus.CANCELLED)
                && sourceChanges.get(pr.id()));
        boolean analysisBlocked = queryRepository.collectionBusy(projectId)
                || prs.stream().anyMatch(FeatureMatchChangeService::analysisNotReady);
        Set<Long> currentPrIds = prs.stream().map(PrRow::id).collect(Collectors.toSet());
        List<Long> removedPrs = savedPrs.keySet().stream().filter(id -> !currentPrIds.contains(id)).sorted().toList();
        return new Changes(state, features, prs, changedPrs, removedPrs, removedFeatures,
                changedFeatures, fullRequired, featureHash, pullRequestChanged, analysisBlocked, reanalysisRequired);
    }

    private boolean sourceChanged(PrRow pr, FeatureMatchCurrentPullRequest previous) {
        if (previous == null) {
            // 아직 대조 가능한 분석 결과가 없는 새 PR은 표시 결과를 낡게 만들지 않는다.
            return pr.analysisStatus() == PullRequestAnalysisStatus.COMPLETED
                    && Objects.equals(pr.headSha(), pr.analysisHeadSha());
        }
        if (previous.getFeatureInputChars() == null || previous.getAnalysisInputChars() == null
                || previous.getSourceSnapshotHash() == null) {
            // 이전 입력을 복구할 수 없는 결과는 실제 입력을 한 번 다시 확인해야 한다.
            return true;
        }
        try {
            if (pr.analysisStatus() != PullRequestAnalysisStatus.COMPLETED
                    || !Objects.equals(pr.headSha(), pr.analysisHeadSha())) {
                // 분석 실패로 요약이 비어도 기존 AI 입력의 요약 예산을 유지해 원본 부분만 비교한다.
                var current = assembler.sourcePullRequest(
                        pr, previous.getFeatureInputChars(), previous.getAnalysisInputChars());
                return !Objects.equals(previous.getSourceSnapshotHash(), FeatureMatchSnapshot.sourceHash(current));
            }
            var current = assembler.pullRequest(pr, previous.getFeatureInputChars());
            return !Objects.equals(previous.getAnalysisSnapshotHash(), FeatureMatchSnapshot.analysisHash(current));
        } catch (FeatureMatchInputTooLargeException exception) {
            return true;
        }
    }

    private static boolean analysisNotReady(PrRow pr) {
        return pr.analysisStatus() == null || pr.analysisStatus() == PullRequestAnalysisStatus.PENDING
                || pr.analysisStatus() == PullRequestAnalysisStatus.RUNNING
                || (pr.analysisStatus() == PullRequestAnalysisStatus.COMPLETED
                && !Objects.equals(pr.headSha(), pr.analysisHeadSha()));
    }
}

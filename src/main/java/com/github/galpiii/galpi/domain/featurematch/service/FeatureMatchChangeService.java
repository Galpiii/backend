package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.PrRow;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchChangesResponse;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentFeature;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentPullRequest;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchCurrentState;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentFeatureRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentPullRequestRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentStateRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
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
            Map<Long, PrRow> currentPrs = queryRepository.pullRequests(item.getProjectId()).stream()
                    .collect(Collectors.toMap(PrRow::id, pr -> pr));
            for (FeatureMatchCurrentPullRequest saved : pullRequestRepository.findAllByProjectId(item.getProjectId())) {
                PrRow pr = currentPrs.get(saved.getPullRequestId());
                if (saved.getSourceSnapshotHash() == null && pr != null
                        && pr.analysisStatus() == PullRequestAnalysisStatus.COMPLETED
                        && Objects.equals(saved.getAnalysisSnapshotHash(), FeatureMatchSnapshot.analysisHash(pr))) {
                    saved.backfillSourceHash(FeatureMatchSnapshot.sourceHash(pr));
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
        List<PrRow> changedPrs = prs.stream()
                .filter(pr -> pr.analysisStatus() == PullRequestAnalysisStatus.COMPLETED
                        && Objects.equals(pr.headSha(), pr.analysisHeadSha()))
                .filter(pr -> sourceChanged(pr, savedPrs.get(pr.id())))
                .toList();
        boolean pullRequestChanged = prs.stream()
                .anyMatch(pr -> sourceChanged(pr, savedPrs.get(pr.id())));
        boolean reanalysisRequired = prs.stream().anyMatch(pr -> savedPrs.containsKey(pr.id())
                && (pr.analysisStatus() == PullRequestAnalysisStatus.FAILED
                || pr.analysisStatus() == PullRequestAnalysisStatus.CANCELLED)
                && sourceChanged(pr, savedPrs.get(pr.id())));
        boolean analysisBlocked = queryRepository.collectionBusy(projectId)
                || prs.stream().anyMatch(FeatureMatchChangeService::analysisNotReady);
        Set<Long> currentPrIds = prs.stream().map(PrRow::id).collect(Collectors.toSet());
        List<Long> removedPrs = savedPrs.keySet().stream().filter(id -> !currentPrIds.contains(id)).sorted().toList();
        return new Changes(state, features, prs, changedPrs, removedPrs, removedFeatures,
                changedFeatures, fullRequired, featureHash, pullRequestChanged, analysisBlocked, reanalysisRequired);
    }

    private static boolean sourceChanged(PrRow pr, FeatureMatchCurrentPullRequest previous) {
        if (previous == null) {
            // 아직 대조 가능한 분석 결과가 없는 새 PR은 표시 결과를 낡게 만들지 않는다.
            return pr.analysisStatus() == PullRequestAnalysisStatus.COMPLETED
                    && Objects.equals(pr.headSha(), pr.analysisHeadSha());
        }
        if (pr.analysisStatus() != PullRequestAnalysisStatus.COMPLETED
                || !Objects.equals(pr.headSha(), pr.analysisHeadSha())) {
            // 분석 실패가 요약을 비워도 PR 원본이 그대로면 이전 대조 결과는 유효하다.
            return !Objects.equals(previous.getSourceSnapshotHash(), FeatureMatchSnapshot.sourceHash(pr));
        }
        return !Objects.equals(previous.getAnalysisSnapshotHash(), FeatureMatchSnapshot.analysisHash(pr));
    }

    private static boolean analysisNotReady(PrRow pr) {
        return pr.analysisStatus() == null || pr.analysisStatus() == PullRequestAnalysisStatus.PENDING
                || pr.analysisStatus() == PullRequestAnalysisStatus.RUNNING
                || (pr.analysisStatus() == PullRequestAnalysisStatus.COMPLETED
                && !Objects.equals(pr.headSha(), pr.analysisHeadSha()));
    }
}

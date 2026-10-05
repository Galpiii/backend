package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.consent.service.AiDataConsentService;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.ProjectRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.PrRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RequirementRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RunRow;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunCreatedResponse;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRun;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunType;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchTarget;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentPullRequestRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentFeatureRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentStateRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeaturePrMatchRepository;
import com.github.galpiii.galpi.domain.featurematch.exception.FeatureMatchInputTooLargeException;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchRunRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchTargetRepository;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchInputAssembler;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchSnapshot;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 프로젝트 잠금 아래에서 전체·부분 대조 실행을 생성한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureMatchRunCreator {

    private final FeatureMatchQueryRepository queryRepository;
    private final FeatureMatchScope scope;
    private final FeatureMatchInputAssembler assembler;
    private final AiDataConsentService consent;
    private final FeatureMatchRunRepository runRepository;
    private final FeatureMatchTargetRepository targetRepository;
    private final ProjectRepository projectRepository;
    private final SpecDocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final PullRequestAnalysisRepository analysisRepository;
    private final FeatureMatchChangeService changes;
    private final FeatureMatchCurrentPullRequestRepository currentPrRepository;
    private final FeatureMatchCurrentFeatureRepository currentFeatureRepository;
    private final FeatureMatchCurrentStateRepository currentStateRepository;
    private final FeaturePrMatchRepository matchRepository;

    @Transactional
    public FeatureMatchRunCreatedResponse create(long projectId, long userId) {
        ProjectRow project = scope.project(projectId, userId, true);
        consent.requireAgreed(userId);
        if (!queryRepository.connected(userId)) {
            log.warn("[기능대조] 실행 생성 거절 projectId={} userId={} code={}",
                    projectId, userId, ErrorCode.GITHUB_REAUTH_REQUIRED.getCode());
            throw new GithubReauthRequiredException();
        }
        RunRow latest = queryRepository.latest(projectId);
        if (latest != null && (latest.status() == FeatureMatchRunStatus.QUEUED
                || latest.status() == FeatureMatchRunStatus.RUNNING)) {
            log.warn("[기능대조] 실행 생성 거절 projectId={} userId={} code={}",
                    projectId, userId, ErrorCode.FEATURE_MATCH_ALREADY_RUNNING.getCode());
            throw new ConflictException(ErrorCode.FEATURE_MATCH_ALREADY_RUNNING);
        }
        Long documentId = project.activeSpecDocumentId();
        if (documentId == null) {
            log.warn("[기능대조] 실행 생성 거절 projectId={} userId={} code={}",
                    projectId, userId, ErrorCode.FEATURE_MATCH_NO_TARGET.getCode());
            throw new ConflictException(ErrorCode.FEATURE_MATCH_NO_TARGET);
        }
        documentRepository.lockById(documentId);
        if (!queryRepository.documentReady(documentId)) {
            log.warn("[기능대조] 실행 생성 거절 projectId={} userId={} code={}",
                    projectId, userId, ErrorCode.FEATURE_MATCH_NO_TARGET.getCode());
            throw new ConflictException(ErrorCode.FEATURE_MATCH_NO_TARGET);
        }
        List<FeatureRow> features = queryRepository.features(documentId);
        List<RequirementRow> requirements = queryRepository.requirements(documentId);
        List<PrRow> pullRequests = queryRepository.pullRequests(projectId);
        if (queryRepository.collectionBusy(projectId)
                || pullRequests.stream().anyMatch(FeatureMatchRunCreator::analysisNotReady)) {
            log.warn("[기능대조] 실행 생성 거절 projectId={} userId={} code={}",
                    projectId, userId, ErrorCode.FEATURE_MATCH_PR_NOT_READY.getCode());
            throw new ConflictException(ErrorCode.FEATURE_MATCH_PR_NOT_READY);
        }
        List<PrRow> eligiblePullRequests = pullRequests.stream()
                .filter(pr -> pr.analysisStatus() == PullRequestAnalysisStatus.COMPLETED)
                .toList();
        if (features.isEmpty() || eligiblePullRequests.isEmpty()) {
            log.warn("[기능대조] 실행 생성 거절 projectId={} userId={} code={}",
                    projectId, userId, ErrorCode.FEATURE_MATCH_NO_TARGET.getCode());
            throw new ConflictException(ErrorCode.FEATURE_MATCH_NO_TARGET);
        }
        try {
            assembler.checkFeatureSize(features, requirements);
        } catch (FeatureMatchInputTooLargeException exception) {
            log.warn("[기능대조] 실행 생성 거절 projectId={} userId={} code={}",
                    projectId, userId, ErrorCode.FEATURE_MATCH_INPUT_TOO_LARGE.getCode());
            throw new BadRequestException(ErrorCode.FEATURE_MATCH_INPUT_TOO_LARGE);
        }
        int failed = (int) pullRequests.stream()
                .filter(pr -> pr.analysisStatus() == PullRequestAnalysisStatus.FAILED).count();
        int cancelled = (int) pullRequests.stream()
                .filter(pr -> pr.analysisStatus() == PullRequestAnalysisStatus.CANCELLED).count();
        if (!currentStateRepository.existsById(projectId)) {
            // 명세서 파일 교체로 이전 기준이 삭제된 경우 남은 스냅샷 행을 정리한다.
            currentPrRepository.deleteAllByProjectId(projectId);
            currentFeatureRepository.deleteAllByProjectId(projectId);
        }
        String hash = FeatureMatchSnapshot.featureHash(features, requirements);
        FeatureMatchRun run = runRepository.save(FeatureMatchRun.queue(
                projectRepository.getReferenceById(projectId), documentRepository.getReferenceById(documentId),
                userRepository.getReferenceById(userId), hash,
                features.size(), eligiblePullRequests.size(), failed, cancelled,
                FeatureMatchRunType.FULL, null,
                FeatureMatchSnapshot.json(FeatureMatchSnapshot.featureHashes(features, requirements))));
        targetRepository.saveAll(eligiblePullRequests.stream()
                .map(pr -> FeatureMatchTarget.pending(run, analysisRepository.getReferenceById(pr.analysisId()),
                        pr.analysisHeadSha(), FeatureMatchSnapshot.analysisHash(pr), FeatureMatchSnapshot.sourceHash(pr),
                        assembler.assemble(features, requirements, pr)))
                .toList());
        log.info("[기능대조] 실행 생성 projectId={} runId={} features={} targets={}",
                projectId, run.getId(), features.size(), eligiblePullRequests.size());
        return new FeatureMatchRunCreatedResponse(run.getId(), run.getStatus(), documentId, features.size(),
                (int) features.stream().filter(f -> f.reviewStatus() == FeatureReviewStatus.UNREVIEWED).count(),
                eligiblePullRequests.size(), failed, cancelled, run.getCreatedAt(), FeatureMatchRunType.FULL, null);
    }

    @Transactional
    public FeatureMatchRunCreatedResponse createPartial(long projectId, long userId) {
        ProjectRow project = scope.project(projectId, userId, true);
        if (project.activeSpecDocumentId() != null) {
            documentRepository.lockById(project.activeSpecDocumentId());
        }
        RunRow latest = queryRepository.latest(projectId);
        if (latest != null && (latest.status() == FeatureMatchRunStatus.QUEUED
                || latest.status() == FeatureMatchRunStatus.RUNNING)) {
            throw new ConflictException(ErrorCode.FEATURE_MATCH_ALREADY_RUNNING);
        }
        var change = changes.changes(projectId, userId);
        if (change.fullRequired()) {
            throw new ConflictException(ErrorCode.FEATURE_MATCH_FULL_REQUIRED);
        }
        if (change.analysisBlocked()) {
            throw new ConflictException(ErrorCode.FEATURE_MATCH_PR_NOT_READY);
        }
        if (!change.stale()) {
            throw new ConflictException(ErrorCode.FEATURE_MATCH_NO_CHANGES);
        }
        List<PrRow> targets = change.changedPullRequests();
        if (targets.isEmpty() && change.removedPullRequestIds().isEmpty()
                && change.removedFeatureIds().isEmpty()) {
            // 기존 결과가 있는 PR의 원본 변경 후 분석 실패·취소만 남으면 결과를 유지한다.
            throw new ConflictException(ErrorCode.FEATURE_MATCH_PR_NOT_READY);
        }
        if (!targets.isEmpty() && change.features().isEmpty()) {
            throw new ConflictException(ErrorCode.FEATURE_MATCH_NO_TARGET);
        }
        if (!targets.isEmpty()) {
            consent.requireAgreed(userId);
            if (!queryRepository.connected(userId)) {
                throw new GithubReauthRequiredException();
            }
        }
        long documentId = project.activeSpecDocumentId();
        List<RequirementRow> requirements = queryRepository.requirements(documentId);
        try {
            assembler.checkFeatureSize(change.features(), requirements);
        } catch (FeatureMatchInputTooLargeException exception) {
            throw new BadRequestException(ErrorCode.FEATURE_MATCH_INPUT_TOO_LARGE);
        }
        Long baseId = change.state().getBaseRun() == null ? null : change.state().getBaseRun().getId();
        int excludedFailed = (int) change.pullRequests().stream()
                .filter(pr -> pr.analysisStatus() == PullRequestAnalysisStatus.FAILED).count();
        int excludedCancelled = (int) change.pullRequests().stream()
                .filter(pr -> pr.analysisStatus() == PullRequestAnalysisStatus.CANCELLED).count();
        FeatureMatchRun run = runRepository.save(FeatureMatchRun.queue(
                projectRepository.getReferenceById(projectId), documentRepository.getReferenceById(documentId),
                userRepository.getReferenceById(userId), change.featureHash(), change.features().size(),
                targets.size(), excludedFailed, excludedCancelled, FeatureMatchRunType.PARTIAL,
                baseId == null ? null : runRepository.getReferenceById(baseId),
                FeatureMatchSnapshot.json(FeatureMatchSnapshot.featureHashes(change.features(), requirements))));
        targetRepository.saveAll(targets.stream().map(pr -> FeatureMatchTarget.pending(run,
                analysisRepository.getReferenceById(pr.analysisId()), pr.analysisHeadSha(),
                FeatureMatchSnapshot.analysisHash(pr), FeatureMatchSnapshot.sourceHash(pr),
                assembler.assemble(change.features(), requirements, pr))).toList());

        Set<Long> removedPrs = Set.copyOf(change.removedPullRequestIds());
        if (!removedPrs.isEmpty()) {
            matchRepository.deleteAiByProjectAndPullRequestIds(projectId, removedPrs);
            currentPrRepository.deleteAllById(removedPrs);
        }
        if (!change.removedFeatureIds().isEmpty()) {
            currentFeatureRepository.deleteAllById(change.removedFeatureIds());
            change.state().update(documentRepository.getReferenceById(documentId),
                    change.state().getBaseRun(), change.featureHash());
        }
        if (targets.isEmpty()) {
            run.finish(FeatureMatchRunStatus.COMPLETED, null);
        }
        return new FeatureMatchRunCreatedResponse(run.getId(), run.getStatus(), documentId,
                change.features().size(), (int) change.features().stream()
                .filter(f -> f.reviewStatus() == FeatureReviewStatus.UNREVIEWED).count(),
                targets.size(), excludedFailed, excludedCancelled, run.getCreatedAt(),
                FeatureMatchRunType.PARTIAL, baseId);
    }

    private static boolean analysisNotReady(PrRow pullRequest) {
        PullRequestAnalysisStatus status = pullRequest.analysisStatus();
        return status == null || status == PullRequestAnalysisStatus.PENDING
                || status == PullRequestAnalysisStatus.RUNNING
                || (status == PullRequestAnalysisStatus.COMPLETED
                && !Objects.equals(pullRequest.headSha(), pullRequest.analysisHeadSha()));
    }
}

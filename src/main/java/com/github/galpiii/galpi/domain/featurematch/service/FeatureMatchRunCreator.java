package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchProjectRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRequirementRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRunRow;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;

import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;

import com.github.galpiii.galpi.domain.consent.service.AiDataConsentService;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunCreatedResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchPullRequestRow;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRun;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchRunStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchTarget;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchRunRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchTargetRepository;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchInputAssembler;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchSnapshot;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.project.repository.ProjectRepository;
import com.github.galpiii.galpi.domain.pullrequest.repository.PullRequestAnalysisRepository;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.domain.featurematch.exception.FeatureMatchInputTooLargeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.List;

/**
 * 이전 실행 정리와 새 실행 생성을 같은 트랜잭션으로 묶는 단일 진입점이다.
 */
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

    @Transactional
    public FeatureMatchRunCreatedResponse create(long projectId, long userId) {
        FeatureMatchProjectRow project = scope.project(projectId, userId, true);
        consent.requireAgreed(userId);
        if (!queryRepository.connected(userId)) {
            throw new ConflictException(ErrorCode.GITHUB_REAUTH_REQUIRED);
        }
        FeatureMatchRunRow latest = queryRepository.latest(projectId);
        if (latest != null && (latest.status() == FeatureMatchRunStatus.QUEUED
                || latest.status() == FeatureMatchRunStatus.RUNNING)) {
            throw new ConflictException(ErrorCode.FEATURE_MATCH_ALREADY_RUNNING);
        }
        Long documentId = project.activeSpecDocumentId();
        if (documentId == null) {
            throw new ConflictException(ErrorCode.FEATURE_MATCH_NO_TARGET);
        }
        queryRepository.lockDocument(documentId);
        if (!queryRepository.documentReady(documentId)) {
            throw new ConflictException(ErrorCode.FEATURE_MATCH_NO_TARGET);
        }
        List<FeatureMatchFeatureRow> features = queryRepository.features(documentId);
        List<FeatureMatchRequirementRow> requirements = queryRepository.requirements(documentId);
        List<FeatureMatchPullRequestRow> pullRequests = queryRepository.pullRequests(projectId);
        if (queryRepository.collectionBusy(projectId)
                || pullRequests.stream().anyMatch(FeatureMatchRunCreator::analysisNotReady)) {
            throw new ConflictException(ErrorCode.FEATURE_MATCH_PR_NOT_READY);
        }
        List<FeatureMatchPullRequestRow> eligiblePullRequests = pullRequests.stream()
                .filter(pr -> pr.analysisStatus() == PullRequestAnalysisStatus.COMPLETED)
                .toList();
        if (features.isEmpty() || eligiblePullRequests.isEmpty()) {
            throw new ConflictException(ErrorCode.FEATURE_MATCH_NO_TARGET);
        }
        try {
            assembler.checkFeatureSize(features, requirements);
        } catch (FeatureMatchInputTooLargeException exception) {
            throw new BadRequestException(ErrorCode.FEATURE_MATCH_INPUT_TOO_LARGE);
        }
        int failed = (int) pullRequests.stream()
                .filter(pr -> pr.analysisStatus() == PullRequestAnalysisStatus.FAILED).count();
        int cancelled = (int) pullRequests.stream()
                .filter(pr -> pr.analysisStatus() == PullRequestAnalysisStatus.CANCELLED).count();
        // 검증 실패는 기존 결과를 보존한다. 삭제와 생성도 한 트랜잭션이므로 생성 실패 시 함께 롤백된다.
        // 실행에 속한 대상·AI 연결·요구사항 연결은 FK CASCADE로 삭제되고 USER 연결은 남는다.
        runRepository.deleteAllForProject(projectId);
        FeatureMatchRun run = runRepository.save(FeatureMatchRun.queue(
                projectRepository.getReferenceById(projectId), documentRepository.getReferenceById(documentId),
                userRepository.getReferenceById(userId), FeatureMatchSnapshot.featureHash(features, requirements),
                features.size(), eligiblePullRequests.size(), failed, cancelled));
        targetRepository.saveAll(eligiblePullRequests.stream()
                .map(pr -> FeatureMatchTarget.pending(run, analysisRepository.getReferenceById(pr.analysisId()),
                        pr.analysisHeadSha(), FeatureMatchSnapshot.analysisHash(pr)))
                .toList());
        log.info("[기능대조] 실행 생성 projectId={} runId={} features={} targets={}",
                projectId, run.getId(), features.size(), eligiblePullRequests.size());
        return new FeatureMatchRunCreatedResponse(run.getId(), run.getStatus(), documentId, features.size(),
                (int) features.stream().filter(f -> f.reviewStatus() == FeatureReviewStatus.UNREVIEWED).count(),
                eligiblePullRequests.size(), failed, cancelled, run.getCreatedAt());
    }

    private static boolean analysisNotReady(FeatureMatchPullRequestRow pullRequest) {
        PullRequestAnalysisStatus status = pullRequest.analysisStatus();
        return status == null || status == PullRequestAnalysisStatus.PENDING
                || status == PullRequestAnalysisStatus.RUNNING
                || (status == PullRequestAnalysisStatus.COMPLETED
                && !Objects.equals(pullRequest.headSha(), pullRequest.analysisHeadSha()));
    }
}

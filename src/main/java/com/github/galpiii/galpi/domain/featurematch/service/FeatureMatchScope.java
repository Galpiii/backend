package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchFeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeaturePrMatchRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchPullRequestRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchProjectRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRequirementRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRunRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchTargetRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRepositoryRow;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchSnapshot;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 결과 조회/수동 편집/워커가 같은 유효성 기준을 사용한다.
 * 소스 버전이나 무효화 이벤트가 없으므로 조회 시 해시 비교도 유지한다.
 * 이를 생략하면 기능 내용 수정이나 PR 재분석 후 이전 근거가 최신 결과처럼 노출될 수 있다.
 */
@Component
@RequiredArgsConstructor
public class FeatureMatchScope {

    private final FeatureMatchQueryRepository queryRepository;

    public record View(FeatureMatchProjectRow project, FeatureMatchRunRow run, List<FeatureMatchFeatureRow> features,
                       List<FeatureMatchRequirementRow> requirements,
                       List<FeatureMatchPullRequestRow> prs, List<FeatureMatchTargetRow> targets,
                       List<FeaturePrMatchRow> matches, List<FeatureMatchRepositoryRow> repositories) {
    }

    public FeatureMatchProjectRow project(long id, long userId, boolean lock) {
        FeatureMatchProjectRow project = queryRepository.project(id, userId, lock);
        if (project == null) {
            throw new NotFoundException(ErrorCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    public long featureProject(long id, long userId) {
        Long project = queryRepository.featureProject(id, userId);
        if (project == null) {
            throw new NotFoundException(ErrorCode.FEATURE_NOT_ACCESSIBLE);
        }
        return project;
    }

    public View result(long projectId, long userId, boolean lock) {
        FeatureMatchProjectRow project = project(projectId, userId, lock);
        if (lock && project.activeSpecDocumentId() != null) {
            queryRepository.lockDocument(project.activeSpecDocumentId());
        }
        FeatureMatchRunRow run = queryRepository.latest(projectId);
        if (run == null) {
            throw new NotFoundException(ErrorCode.FEATURE_MATCH_RESULT_NOT_FOUND);
        }
        switch (run.status()) {
            case QUEUED, RUNNING -> throw new ConflictException(ErrorCode.FEATURE_MATCH_RESULT_RUNNING);
            case FAILED, CANCELLED -> throw new ConflictException(ErrorCode.FEATURE_MATCH_RESULT_UNAVAILABLE);
            default -> {
            }
        }
        List<FeatureMatchFeatureRow> features = queryRepository.features(run.specDocumentId());
        List<FeatureMatchRequirementRow> requirements = queryRepository.requirements(run.specDocumentId());
        List<FeatureMatchPullRequestRow> prs = queryRepository.pullRequests(projectId);
        List<FeatureMatchTargetRow> targets = queryRepository.targets(run.id());
        if (!Objects.equals(project.activeSpecDocumentId(), run.specDocumentId())
                || !run.featureSnapshotHash().equals(FeatureMatchSnapshot.featureHash(features, requirements))
                || targets.size() != run.eligiblePrCount()) {
            stale();
        }
        Map<Long, FeatureMatchPullRequestRow> byAnalysis = prs.stream().filter(pr -> pr.analysisId() != null)
                .collect(Collectors.toMap(FeatureMatchPullRequestRow::analysisId, pr -> pr));
        for (FeatureMatchTargetRow target : targets) {
            FeatureMatchPullRequestRow pr = byAnalysis.get(target.pullRequestAnalysisId());
            if (!current(target, pr)) {
                stale();
            }
        }
        return new View(project, run, features, requirements, prs, targets,
                queryRepository.matches(run.id(), run.specDocumentId()), queryRepository.repositories(projectId));
    }

    public boolean current(FeatureMatchTargetRow target, FeatureMatchPullRequestRow pr) {
        return pr != null && pr.analysisStatus() == PullRequestAnalysisStatus.COMPLETED
                && Objects.equals(pr.headSha(), pr.analysisHeadSha())
                && Objects.equals(target.analysisHeadSha(), pr.analysisHeadSha())
                && target.analysisSnapshotHash().equals(FeatureMatchSnapshot.analysisHash(pr));
    }

    public void repositoryScope(View view, Long repositoryId) {
        if (repositoryId != null && view.repositories().stream().noneMatch(run -> run.id() == repositoryId)) {
            throw new NotFoundException(ErrorCode.PROJECT_REPOSITORY_NOT_FOUND);
        }
    }

    private static void stale() {
        throw new ConflictException(ErrorCode.FEATURE_MATCH_RESULT_STALE);
    }
}

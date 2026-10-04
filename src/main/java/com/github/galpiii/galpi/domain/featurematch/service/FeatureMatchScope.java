package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.ProjectRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.PrRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RepositoryRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RequirementRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RunRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.TargetRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.MatchRow;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.support.FeatureMatchSnapshot;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.domain.pullrequest.entity.PullRequestAnalysisStatus;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RequiredArgsConstructor
public class FeatureMatchScope {

    private final FeatureMatchQueryRepository queryRepository;
    private final SpecDocumentRepository documentRepository;

    public record View(ProjectRow project, RunRow run, List<FeatureRow> features,
                       List<RequirementRow> requirements,
                       List<PrRow> prs, List<TargetRow> targets,
                       List<MatchRow> matches, List<RepositoryRow> repositories) {
    }

    public ProjectRow project(long id, long userId, boolean lock) {
        ProjectRow project = queryRepository.project(id, userId, lock);
        if (project == null) {
            log.warn("[기능대조] 프로젝트 접근 거절 projectId={} userId={}", id, userId);
            throw new NotFoundException(ErrorCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    public long featureProject(long id, long userId) {
        Long project = queryRepository.featureProject(id, userId);
        if (project == null) {
            log.warn("[기능대조] 기능 접근 거절 featureId={} userId={}", id, userId);
            throw new NotFoundException(ErrorCode.FEATURE_NOT_ACCESSIBLE);
        }
        return project;
    }

    public View result(long projectId, long userId, boolean lock) {
        ProjectRow project = project(projectId, userId, lock);
        if (lock && project.activeSpecDocumentId() != null) {
            documentRepository.lockById(project.activeSpecDocumentId());
        }
        RunRow run = queryRepository.latest(projectId);
        if (run == null) {
            log.warn("[기능대조] 결과 없음 projectId={} userId={}", projectId, userId);
            throw new NotFoundException(ErrorCode.FEATURE_MATCH_RESULT_NOT_FOUND);
        }
        switch (run.status()) {
            case QUEUED, RUNNING -> {
                log.debug("[기능대조] 진행 중 결과 조회 거절 projectId={} runId={}", projectId, run.id());
                throw new ConflictException(ErrorCode.FEATURE_MATCH_RESULT_RUNNING);
            }
            case FAILED, CANCELLED -> {
                log.warn("[기능대조] 종료 상태 조회 거절 runId={} status={}", run.id(), run.status());
                throw new ConflictException(ErrorCode.FEATURE_MATCH_RESULT_UNAVAILABLE);
            }
            default -> {
            }
        }
        List<FeatureRow> features = queryRepository.features(run.specDocumentId());
        List<RequirementRow> requirements = queryRepository.requirements(run.specDocumentId());
        List<PrRow> prs = queryRepository.pullRequests(projectId);
        List<TargetRow> targets = queryRepository.targets(run.id());
        if (!Objects.equals(project.activeSpecDocumentId(), run.specDocumentId())
                || !run.featureSnapshotHash().equals(FeatureMatchSnapshot.featureHash(features, requirements))
                || targets.size() != run.eligiblePrCount()) {
            stale(projectId, run.id());
        }
        Map<Long, PrRow> byAnalysis = prs.stream().filter(pr -> pr.analysisId() != null)
                .collect(Collectors.toMap(PrRow::analysisId, pr -> pr));
        for (TargetRow target : targets) {
            PrRow pr = byAnalysis.get(target.pullRequestAnalysisId());
            if (!current(target, pr)) {
                stale(projectId, run.id());
            }
        }
        return new View(project, run, features, requirements, prs, targets,
                queryRepository.matches(run.id(), run.specDocumentId()), queryRepository.repositories(projectId));
    }

    public boolean current(TargetRow target, PrRow pr) {
        return pr != null && pr.analysisStatus() == PullRequestAnalysisStatus.COMPLETED
                && Objects.equals(pr.headSha(), pr.analysisHeadSha())
                && Objects.equals(target.analysisHeadSha(), pr.analysisHeadSha())
                && target.analysisSnapshotHash().equals(FeatureMatchSnapshot.analysisHash(pr));
    }

    public void repositoryScope(View view, Long repositoryId) {
        if (repositoryId != null && view.repositories().stream().noneMatch(run -> run.id() == repositoryId)) {
            log.warn("[기능대조] 저장소 범위 거절 projectId={} repositoryId={}", view.project().id(), repositoryId);
            throw new NotFoundException(ErrorCode.PROJECT_REPOSITORY_NOT_FOUND);
        }
    }

    private static void stale(long projectId, long runId) {
        log.debug("[기능대조] 변경된 실행 기준 조회 거절 projectId={} runId={}", projectId, runId);
        throw new ConflictException(ErrorCode.FEATURE_MATCH_RESULT_STALE);
    }
}

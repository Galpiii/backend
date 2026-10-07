package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.FeatureRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.ProjectRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.PrRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RepositoryRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RequirementRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RunRow;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.MatchRow;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchCurrentStateRepository;
import com.github.galpiii.galpi.domain.featurespec.repository.SpecDocumentRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.ConflictException;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 현재 표시 결과의 접근 범위와 기준 실행을 읽는다. 입력의 최신 여부는 변경 조회 서비스가 계산한다. */
@Component
@Slf4j
@RequiredArgsConstructor
public class FeatureMatchScope {

    private final FeatureMatchQueryRepository queryRepository;
    private final SpecDocumentRepository documentRepository;
    private final FeatureMatchCurrentStateRepository currentStateRepository;

    public record View(ProjectRow project, RunRow run, List<FeatureRow> features,
                       List<RequirementRow> requirements,
                       List<PrRow> prs,
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
        var current = currentStateRepository.findById(projectId).orElse(null);
        RunRow run = current == null || current.getBaseRun() == null
                ? queryRepository.latest(projectId) : queryRepository.run(current.getBaseRun().getId());
        if (run == null) {
            log.warn("[기능대조] 결과 없음 projectId={} userId={}", projectId, userId);
            throw new NotFoundException(ErrorCode.FEATURE_MATCH_RESULT_NOT_FOUND);
        }
        if (current == null) {
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
        }
        if (current == null || !Objects.equals(project.activeSpecDocumentId(), current.getSpecDocument().getId())) {
            throw new NotFoundException(ErrorCode.FEATURE_MATCH_RESULT_NOT_FOUND);
        }
        List<FeatureRow> features = queryRepository.features(run.specDocumentId());
        List<RequirementRow> requirements = queryRepository.requirements(run.specDocumentId());
        List<PrRow> prs = queryRepository.pullRequests(projectId);
        return new View(project, run, features, requirements, prs,
                queryRepository.matches(run.specDocumentId()), queryRepository.repositories(projectId));
    }

    public void repositoryScope(View view, Long repositoryId) {
        if (repositoryId != null && view.repositories().stream().noneMatch(run -> run.id() == repositoryId)) {
            log.warn("[기능대조] 저장소 범위 거절 projectId={} repositoryId={}", view.project().id(), repositoryId);
            throw new NotFoundException(ErrorCode.PROJECT_REPOSITORY_NOT_FOUND);
        }
    }

}

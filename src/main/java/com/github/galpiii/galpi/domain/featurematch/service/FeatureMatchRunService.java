package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.Counts;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRows.RunRow;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunCreatedResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunStatusResponse;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Slf4j
@RequiredArgsConstructor
public class FeatureMatchRunService {

    private final FeatureMatchQueryRepository queryRepository;
    private final FeatureMatchRunCreator creator;

    public FeatureMatchRunCreatedResponse create(long projectId, long userId) {
        return creator.create(projectId, userId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeatureMatchRunStatusResponse latest(long projectId, long userId) {
        if (queryRepository.project(projectId, userId, false) == null) {
            log.warn("[기능대조] 최신 실행 프로젝트 접근 거절 projectId={} userId={}", projectId, userId);
            throw new NotFoundException(ErrorCode.PROJECT_NOT_FOUND);
        }
        RunRow run = queryRepository.latest(projectId);
        if (run == null) {
            throw new NotFoundException(ErrorCode.FEATURE_MATCH_RUN_NOT_FOUND);
        }
        return statusResponse(run);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeatureMatchRunStatusResponse status(long runId, long userId) {
        RunRow run = queryRepository.run(runId);
        if (run == null || queryRepository.project(run.projectId(), userId, false) == null) {
            log.warn("[기능대조] 실행 접근 거절 runId={} userId={}", runId, userId);
            throw new NotFoundException(ErrorCode.FEATURE_MATCH_RUN_NOT_FOUND);
        }
        return statusResponse(run);
    }

    private FeatureMatchRunStatusResponse statusResponse(RunRow run) {
        Counts counts = queryRepository.counts(run.id());
        int progress = (counts.completedCount() + counts.failedCount() + counts.cancelledCount())
                * 100 / run.eligiblePrCount();
        return new FeatureMatchRunStatusResponse(run.id(), run.status(), run.specDocumentId(), run.featureCount(), run.eligiblePrCount(),
                counts.pendingCount(), counts.runningCount(), counts.completedCount(), counts.failedCount(),
                counts.cancelledCount(), progress, run.failureCode(), run.startedAt(), run.finishedAt(), run.createdAt());
    }
}

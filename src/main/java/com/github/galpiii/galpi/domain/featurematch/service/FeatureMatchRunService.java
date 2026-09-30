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

@Service
@Slf4j
@RequiredArgsConstructor
public class FeatureMatchRunService {

    private final FeatureMatchQueryRepository queryRepository;
    private final FeatureMatchRunCreator creator;

    public FeatureMatchRunCreatedResponse create(long projectId, long userId) {
        return creator.create(projectId, userId);
    }

    @Transactional(readOnly = true)
    public FeatureMatchRunStatusResponse status(long runId, long userId) {
        RunRow run = queryRepository.run(runId);
        if (run == null || queryRepository.project(run.projectId(), userId, false) == null) {
            log.warn("[기능대조] 실행 접근 거절 runId={} userId={}", runId, userId);
            throw new NotFoundException(ErrorCode.FEATURE_MATCH_RUN_NOT_FOUND);
        }
        Counts counts = queryRepository.counts(runId);
        int progress = (counts.completedCount() + counts.failedCount() + counts.cancelledCount())
                * 100 / run.eligiblePrCount();
        return new FeatureMatchRunStatusResponse(runId, run.status(), run.featureCount(), run.eligiblePrCount(),
                counts.pendingCount(), counts.runningCount(), counts.completedCount(), counts.failedCount(),
                counts.cancelledCount(), progress, run.failureCode(), run.startedAt(), run.finishedAt(), run.createdAt());
    }
}

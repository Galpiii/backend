package com.github.galpiii.galpi.domain.featurematch.service;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchCounts;
import com.github.galpiii.galpi.domain.featurematch.dto.FeatureMatchRunRow;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunCreatedResponse;
import com.github.galpiii.galpi.domain.featurematch.dto.response.FeatureMatchRunStatusResponse;
import com.github.galpiii.galpi.domain.featurematch.repository.FeatureMatchQueryRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeatureMatchRunService {

    private final FeatureMatchQueryRepository queryRepository;
    private final FeatureMatchRunCreator creator;

    public FeatureMatchRunCreatedResponse create(long projectId, long userId) {
        return creator.create(projectId, userId);
    }

    @Transactional(readOnly = true)
    public FeatureMatchRunStatusResponse status(long runId, long userId) {
        FeatureMatchRunRow run = queryRepository.run(runId);
        if (run == null || queryRepository.project(run.projectId(), userId, false) == null) {
            throw new NotFoundException(ErrorCode.FEATURE_MATCH_RUN_NOT_FOUND);
        }
        FeatureMatchCounts counts = queryRepository.counts(runId);
        int progress = (counts.completedCount() + counts.failedCount() + counts.cancelledCount())
                * 100 / run.eligiblePrCount();
        return new FeatureMatchRunStatusResponse(runId, run.status(), run.featureCount(), run.eligiblePrCount(),
                counts.pendingCount(), counts.runningCount(), counts.completedCount(), counts.failedCount(),
                counts.cancelledCount(), progress, run.failureCode(), run.startedAt(), run.finishedAt(), run.createdAt());
    }
}

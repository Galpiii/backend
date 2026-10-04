package com.github.galpiii.galpi.domain.analysis.dto;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunTarget;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunStatus;
import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunTargetStatus;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import java.util.List;

/** Each connected repository's own latest run, independent of project.lastAnalysisRunId. */
public record RepositoryAnalysisStatusResponse(
        Long repositoryId, Long analysisRunId, String status, List<IncompleteReason> incompleteReasons
) {
    public static RepositoryAnalysisStatusResponse notAnalyzed(Long repositoryId) {
        return new RepositoryAnalysisStatusResponse(repositoryId, null, "NOT_ANALYZED", List.of());
    }

    public static RepositoryAnalysisStatusResponse from(AnalysisRunTarget target) {
        AnalysisRunStatus run = target.getAnalysisRun().getStatus();
        boolean unfinished = target.getStatus() == AnalysisRunTargetStatus.PENDING
                || target.getStatus() == AnalysisRunTargetStatus.COLLECTING;
        // Rate-limited runs also require an explicit retry; they do not resume automatically.
        boolean stopped = run.isTerminal() || run == AnalysisRunStatus.RATE_LIMITED;
        String status = stopped && unfinished ? run.name() : target.getStatus().name();
        return new RepositoryAnalysisStatusResponse(target.getRepository().getId(),
                target.getAnalysisRun().getId(), status,
                target.getIncompleteReasons() == null ? List.of() : target.getIncompleteReasons());
    }
}

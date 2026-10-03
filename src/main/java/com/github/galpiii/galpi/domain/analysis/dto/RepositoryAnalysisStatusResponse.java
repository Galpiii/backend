package com.github.galpiii.galpi.domain.analysis.dto;

import com.github.galpiii.galpi.domain.analysis.entity.AnalysisRunTarget;
import com.github.galpiii.galpi.domain.collection.entity.IncompleteReason;
import java.util.List;

/** Each connected repository's own latest run, independent of project.lastAnalysisRunId. */
public record RepositoryAnalysisStatusResponse(
        Long repositoryId, Long analysisRunId, String status, List<IncompleteReason> incompleteReasons
) {
    public static RepositoryAnalysisStatusResponse from(AnalysisRunTarget target) {
        String status = target.getAnalysisRun().getStatus().name().equals("CANCELLED")
                ? "CANCELLED" : target.getStatus().name();
        return new RepositoryAnalysisStatusResponse(target.getRepository().getId(),
                target.getAnalysisRun().getId(), status,
                target.getIncompleteReasons() == null ? List.of() : target.getIncompleteReasons());
    }
}

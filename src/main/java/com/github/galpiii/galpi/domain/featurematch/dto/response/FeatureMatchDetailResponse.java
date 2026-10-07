package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureEvidenceStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchSource;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record FeatureMatchDetailResponse(long featureMatchRunId, long featureId, String name,
                                         FeatureReviewStatus reviewStatus, FeatureEvidenceStatus evidenceStatus,
                                         Long sectionId,
                                         String sectionTitle,
                                         Long repositoryScope, Integer sourcePageStart, Integer sourcePageEnd,
                                         List<Requirement> requirements, long relatedPullRequestCount,
                                         List<RepositoryGroup> repositories,
                                         @Schema(description = "기능별이 아닌 프로젝트 전체 현재 결과의 최신 여부")
                                         FeatureMatchChangesResponse.Freshness freshness) {
    @Schema(name = "FeatureMatchDetailRequirement")
    public record Requirement(long requirementId, String content, long relatedPullRequestCount) {
    }

    @Schema(name = "FeatureMatchDetailRepositoryGroup")
    public record RepositoryGroup(long repositoryId, String fullName, int relatedPullRequestCount,
                                  List<Match> pullRequests) {
    }

    @Schema(name = "FeatureMatchDetailMatch")
    public record Match(long matchId, FeatureMatchSource source, String reason,
                        List<MatchedRequirement> matchedRequirements,
                        FeatureMatchPullRequestResponse pullRequest) {
    }

    @Schema(name = "FeatureMatchDetailMatchedRequirement")
    public record MatchedRequirement(long requirementId, String content) {
    }
}

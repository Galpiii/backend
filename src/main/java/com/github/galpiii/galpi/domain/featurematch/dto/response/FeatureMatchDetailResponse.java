package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.featurematch.dto.FeatureEvidenceStatus;
import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchSource;
import com.github.galpiii.galpi.domain.featurespec.entity.FeatureReviewStatus;

import java.util.List;

public record FeatureMatchDetailResponse(long featureMatchRunId, long featureId, String name,
                                         FeatureReviewStatus reviewStatus, FeatureEvidenceStatus evidenceStatus,
                                         Long sectionId,
                                         String sectionTitle,
                                         Long repositoryScope, Integer sourcePageStart, Integer sourcePageEnd,
                                         List<Requirement> requirements, long relatedPullRequestCount,
                                         List<RepositoryGroup> repositories) {
    public record Requirement(long requirementId, String content, long relatedPullRequestCount) {
    }

    public record RepositoryGroup(long repositoryId, String fullName, int relatedPullRequestCount,
                                  List<Match> pullRequests) {
    }

    public record Match(long matchId, FeatureMatchSource source, String reason,
                        List<MatchedRequirement> matchedRequirements,
                        FeatureMatchPullRequestResponse pullRequest) {
    }

    public record MatchedRequirement(long requirementId, String content) {
    }
}

package com.github.galpiii.galpi.domain.featurematch.dto.response;

import java.util.List;

public record UnmatchedPullRequestListResponse(long featureMatchRunId,
                                               List<FeatureMatchPullRequestResponse> pullRequests,
                                               int page, int size, long totalElements, int totalPages) {
}

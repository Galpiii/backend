package com.github.galpiii.galpi.domain.featurematch.dto.response;

import com.github.galpiii.galpi.domain.featurematch.entity.FeatureMatchSource;

import java.time.OffsetDateTime;
import java.util.List;

public record FeaturePrMatchesCreatedResponse(List<CreatedMatch> createdMatches) {
    public record CreatedMatch(long matchId, long featureId, long pullRequestId, FeatureMatchSource source,
                               OffsetDateTime createdAt) {
    }
}

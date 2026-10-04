package com.github.galpiii.galpi.ai.dto;

import java.util.List;

public record FeatureMatchingResult(List<Match> matches) {
    public record Match(Long featureId, String reason, List<Long> requirementIds) {
    }
}

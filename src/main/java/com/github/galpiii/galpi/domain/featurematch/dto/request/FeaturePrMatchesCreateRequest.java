package com.github.galpiii.galpi.domain.featurematch.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record FeaturePrMatchesCreateRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> pullRequestIds) {
}

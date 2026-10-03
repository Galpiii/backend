package com.github.galpiii.galpi.domain.analysis.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Galpi internal repository IDs; never GitHub repository IDs. */
public record SelectedAnalysisRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> repositoryIds
) {}

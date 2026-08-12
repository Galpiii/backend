package com.github.galpiii.galpi.domain.project.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record LinkRepositoriesRequest(
        @NotEmpty(message = "연결할 저장소를 하나 이상 선택해야 합니다.")
        List<@NotNull Long> githubRepositoryIds
) {
}

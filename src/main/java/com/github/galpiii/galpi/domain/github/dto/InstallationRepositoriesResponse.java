package com.github.galpiii.galpi.domain.github.dto;

import java.util.List;

/** installation 단위로 묶은 저장소 목록. 개인 계정과 조직이 각각 한 덩어리가 된다. */
public record InstallationRepositoriesResponse(
        InstallationSummaryResponse installation,
        List<SelectableRepositoryResponse> repositories
) {
}

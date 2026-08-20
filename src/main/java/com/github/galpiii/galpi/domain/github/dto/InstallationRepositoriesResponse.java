package com.github.galpiii.galpi.domain.github.dto;

import java.util.List;

/**
 * installation 단위로 묶은 저장소 목록. 개인 계정과 조직이 각각 한 덩어리가 된다.
 *
 * @param truncated 페이지 상한에 걸려 이 설치의 저장소를 끝까지 읽지 못했으면 {@code true}.
 *                  목록에 없는 저장소가 "없는 저장소"가 아니라는 표시다
 */
public record InstallationRepositoriesResponse(
        InstallationSummaryResponse installation,
        List<SelectableRepositoryResponse> repositories,
        boolean truncated
) {
}

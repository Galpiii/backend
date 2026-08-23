package com.github.galpiii.galpi.domain.github.dto;

import java.util.List;

/**
 * 저장소 선택 화면에 필요한 것 전부.
 *
 * <p>installation 하나가 죽어도 나머지 저장소는 그대로 보여야 한다. 실패한 설치를 목록에서
 * 지우고 조용히 넘어가면 사용자는 "왜 그 조직 저장소가 안 보이지"를 알 방법이 없고,
 * 반대로 전체를 실패시키면 멀쩡한 저장소까지 못 고른다. 그래서 성공과 실패를 갈라 함께 준다.
 *
 * @param truncated 이 목록이 전부가 아니면 {@code true}. 설치 목록 자체가 잘렸거나, 어느
 *                  설치의 저장소 페이지가 잘렸거나, 요청 budget이 떨어져 뒤쪽 설치를 아예
 *                  훑지 못한 경우다. 마지막 경우는 해당 설치가 성공 목록에도 실패 목록에도
 *                  나타나지 않으므로 이 플래그 말고는 알릴 방법이 없다
 */
public record SelectableRepositoriesResponse(
        List<InstallationRepositoriesResponse> installations,
        List<FailedInstallationResponse> failedInstallations,
        boolean truncated
) {
}

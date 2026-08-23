package com.github.galpiii.galpi.domain.github.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 연결 해제 결과.
 *
 * <p>{@code authorizationRevoked}가 false면 갈피 쪽 정리는 끝났지만 GitHub에는 authorization이
 * 남아 있을 수 있다. 저장된 토큰이 없거나 만료됐으면 폐기 API를 부를 수단 자체가 없기 때문이다
 * — 해제만을 위해 재인증을 강요하지 않고, 대신 직접 해제할 링크를 준다.
 */
@Schema(description = "GitHub 연결 해제 결과")
public record GithubDisconnectResponse(
        @Schema(description = "GitHub에서 App authorization 폐기를 확인했는지. "
                + "false면 사용자에게 authorizationsUrl 안내가 필요하다")
        boolean authorizationRevoked,
        @Schema(description = "사용자가 직접 App 권한을 해제하는 GitHub 설정 페이지")
        String authorizationsUrl,
        @Schema(description = "App 설치를 직접 제거하는 GitHub 설정 페이지. "
                + "설치는 연결 해제로 지우지 않는다 — 조직 설치는 다른 사용자와 공유될 수 있다")
        String installationsUrl
) {
}

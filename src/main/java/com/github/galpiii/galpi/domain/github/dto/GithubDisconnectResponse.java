package com.github.galpiii.galpi.domain.github.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 연결 해제 결과.
 *
 * <p>{@code authorizationRevoked}가 false면 갈피 쪽 정리는 끝났지만 GitHub에는 authorization이
 * 남아 있을 수 있다. 저장된 토큰이 없거나 만료됐으면 폐기 API를 부를 수단 자체가 없고, 폐기
 * 호출이 실패했을 수도 있다 — 해제만을 위해 재인증을 강요하지 않고, 대신 직접 해제할 링크를
 * 준다.
 *
 * <p>true여도 갈피가 보장하는 것은 <b>보관하던 access token의 회수</b>와 그 폐기 호출을 GitHub이
 * 받아들였다는 사실까지다. 만료형 토큰을 쓰는 앱이 함께 발급하는 refresh token은 갈피가
 * 저장하지 않아 폐기 대상에도 들어가지 않는다. 그래서 링크는 실패했을 때만 쓰는 대비책이
 * 아니라 언제나 유효한 안내다.
 */
@Schema(description = "GitHub 연결 해제 결과")
public record GithubDisconnectResponse(
        @Schema(description = "GitHub에서 App authorization 폐기를 확인했는지. "
                + "false면 authorization이 남아 있으므로 authorizationsUrl 안내가 필요하다. "
                + "false여도 갈피가 보관하던 토큰은 폐기했거나 재시도 큐에 남겨 회수한다")
        boolean authorizationRevoked,
        @Schema(description = "사용자가 직접 App 권한을 해제하는 GitHub 설정 페이지")
        String authorizationsUrl,
        @Schema(description = "App 설치를 직접 제거하는 GitHub 설정 페이지. "
                + "설치는 연결 해제로 지우지 않는다 — 조직 설치는 다른 사용자와 공유될 수 있다")
        String installationsUrl
) {
}

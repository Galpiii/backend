package com.github.galpiii.galpi.domain.auth.jwt;

import java.time.Instant;

/**
 * @param sessionStartedAt 이 세션이 처음 만들어진 시각. 회전할 때마다 그대로 물려주므로 절대 수명을
 *                         잴 수 있다. Refresh 토큰에만 있고 Access 토큰에서는 {@code null}이다.
 */
public record TokenClaims(
        Long userId,
        String jti,
        TokenType type,
        Instant expiresAt,
        Instant sessionStartedAt
) {
}

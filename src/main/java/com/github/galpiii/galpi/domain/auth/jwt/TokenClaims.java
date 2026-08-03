package com.github.galpiii.galpi.domain.auth.jwt;

import java.time.Instant;

public record TokenClaims(
        Long userId,
        String jti,
        TokenType type,
        Instant expiresAt,
        Instant sessionStartedAt
) {
}

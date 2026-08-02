package com.github.galpiii.galpi.domain.auth.support;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RefreshCookieFactory {

    private final JwtProperties jwtProperties;

    public ResponseCookie create(String refreshToken) {
        return build(refreshToken, jwtProperties.refreshTokenTtl());
    }

    public ResponseCookie expired() {
        return build("", Duration.ZERO);
    }

    public String cookieName() {
        return jwtProperties.cookie().name();
    }

    private ResponseCookie build(String value, Duration maxAge) {
        JwtProperties.Cookie cookie = jwtProperties.cookie();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(cookie.name(), value)
                .httpOnly(true)
                .secure(cookie.secure())
                .path(cookie.path())
                .sameSite(cookie.sameSite())
                .maxAge(maxAge);
        if (!cookie.domain().isBlank()) {
            builder.domain(cookie.domain());
        }
        return builder.build();
    }
}

package com.github.galpiii.galpi.domain.auth.support;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Refresh 토큰 쿠키 생성. HttpOnly로만 내보내며 자바스크립트에서 읽히지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RefreshCookieFactory {

    private final JwtProperties jwtProperties;

    public ResponseCookie create(String refreshToken) {
        return build(refreshToken, jwtProperties.refreshTokenTtl());
    }

    /**
     * 로그아웃·재사용 감지 시 즉시 만료시킨다.
     */
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

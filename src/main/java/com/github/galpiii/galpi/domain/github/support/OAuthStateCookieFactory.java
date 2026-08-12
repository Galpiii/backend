package com.github.galpiii.galpi.domain.github.support;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.github.store.OAuthStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class OAuthStateCookieFactory {

    public static final String COOKIE_NAME = "galpi_oauth_state";

    private static final String PATH = "/auth/github";

    private final JwtProperties jwtProperties;

    public ResponseCookie create(String state) {
        return build(state, OAuthStateStore.TTL);
    }

    public ResponseCookie expired() {
        return build("", Duration.ZERO);
    }

    private ResponseCookie build(String value, Duration maxAge) {
        JwtProperties.Cookie cookie = jwtProperties.cookie();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookie.secure())
                .path(PATH)
                .sameSite("Lax")
                .maxAge(maxAge);
        if (!cookie.domain().isBlank()) {
            builder.domain(cookie.domain());
        }
        return builder.build();
    }
}

package com.github.galpiii.galpi.domain.github.controller;

import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.github.dto.AuthorizeRedirect;
import com.github.galpiii.galpi.domain.github.store.OAuthStateStore;
import com.github.galpiii.galpi.domain.github.support.OAuthStateCookieFactory;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.support.WebMvcTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("GitHub 컨트롤러 — 인증 시작·콜백·연결 해제")
class GithubAuthControllerTest extends WebMvcTestSupport {

    private static final long USER_ID = 7L;
    private static final String FRONTEND_CALLBACK = "https://galpi.dev/auth/callback";
    private static final String STATE = "random-state-value";
    private static final String STATE_COOKIE = OAuthStateCookieFactory.COOKIE_NAME;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private String bearer() {
        return "Bearer " + tokenProvider.createAccessToken(USER_ID);
    }

    private static AuthorizeRedirect authorizeRedirect() {
        return new AuthorizeRedirect(
                "https://github.com/login/oauth/authorize?state=" + STATE, STATE);
    }

    @Nested
    @DisplayName("인증 시작 — GET /auth/github/authorize")
    class Authorize {

        @Test
        @DisplayName("GitHub 인증 페이지로 302 리다이렉트한다")
        void redirectsToGithub() throws Exception {
            given(githubOAuthService.buildAuthorizeRedirect(isNull())).willReturn(authorizeRedirect());

            mockMvc.perform(get("/auth/github/authorize"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("https://github.com/login/oauth/authorize?state=" + STATE));
        }

        @Test
        @DisplayName("returnTo를 서비스로 그대로 넘긴다")
        void passesReturnTo() throws Exception {
            given(githubOAuthService.buildAuthorizeRedirect("/projects/3")).willReturn(authorizeRedirect());

            mockMvc.perform(get("/auth/github/authorize").param("returnTo", "/projects/3"))
                    .andExpect(status().isFound());

            verify(githubOAuthService).buildAuthorizeRedirect("/projects/3");
        }

        @Test
        @DisplayName("리다이렉트 응답을 캐시하지 않는다")
        void forbidsCaching() throws Exception {
            given(githubOAuthService.buildAuthorizeRedirect(any())).willReturn(authorizeRedirect());

            mockMvc.perform(get("/auth/github/authorize"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }

        @Test
        @DisplayName("state를 HttpOnly 쿠키로 심어 이 브라우저에 묶는다")
        void bindsStateToBrowser() throws Exception {
            given(githubOAuthService.buildAuthorizeRedirect(any())).willReturn(authorizeRedirect());

            mockMvc.perform(get("/auth/github/authorize"))
                    .andExpect(cookie().value(STATE_COOKIE, STATE))
                    .andExpect(cookie().httpOnly(STATE_COOKIE, true))
                    .andExpect(cookie().path(STATE_COOKIE, "/auth/github"))
                    .andExpect(cookie().maxAge(STATE_COOKIE, (int) OAuthStateStore.TTL.toSeconds()));
        }

        @Test
        @DisplayName("state 쿠키는 Lax다 — None이면 막으려던 교차 사이트 유도가 다시 열린다")
        void keepsStateCookieSameSiteLax() throws Exception {
            given(githubOAuthService.buildAuthorizeRedirect(any())).willReturn(authorizeRedirect());

            mockMvc.perform(get("/auth/github/authorize"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")));
        }

        @Test
        @DisplayName("GitHub까지 가지 않는 오류 경로에서는 남아 있던 state 쿠키를 지운다")
        void clearsStateCookieOnErrorPath() throws Exception {
            given(githubOAuthService.buildAuthorizeRedirect(any()))
                    .willReturn(AuthorizeRedirect.withoutState(FRONTEND_CALLBACK + "?error=GITHUB-007"));

            mockMvc.perform(get("/auth/github/authorize").param("returnTo", "https://evil.example.com"))
                    .andExpect(cookie().value(STATE_COOKIE, ""))
                    .andExpect(cookie().maxAge(STATE_COOKIE, 0));
        }
    }

    @Nested
    @DisplayName("콜백 — GET /auth/github/callback")
    class Callback {

        @Test
        @DisplayName("로그인 코드를 실은 프론트 URL로 302 리다이렉트한다")
        void redirectsToFrontendWithLoginCode() throws Exception {
            given(githubOAuthService.handleCallback("gh-code", STATE, STATE, null, null))
                    .willReturn(FRONTEND_CALLBACK + "?code=one-time");

            mockMvc.perform(get("/auth/github/callback")
                            .param("code", "gh-code")
                            .param("state", STATE)
                            .cookie(new Cookie(STATE_COOKIE, STATE)))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl(FRONTEND_CALLBACK + "?code=one-time"));
        }

        @Test
        @DisplayName("실패해도 JSON이 아니라 프론트 오류 화면으로 302한다")
        void redirectsInsteadOfReturningJson() throws Exception {
            given(githubOAuthService.handleCallback(any(), any(), any(), any(), any()))
                    .willReturn(FRONTEND_CALLBACK + "?error=" + ErrorCode.GITHUB_OAUTH_STATE_INVALID.getCode());

            mockMvc.perform(get("/auth/github/callback").param("code", "gh-code"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl(
                            FRONTEND_CALLBACK + "?error=" + ErrorCode.GITHUB_OAUTH_STATE_INVALID.getCode()));
        }

        @Test
        @DisplayName("GitHub이 준 error 파라미터를 서비스로 넘긴다")
        void forwardsGithubError() throws Exception {
            given(githubOAuthService.handleCallback(isNull(), any(), any(), any(), any()))
                    .willReturn(FRONTEND_CALLBACK + "?error=GITHUB-004");

            mockMvc.perform(get("/auth/github/callback")
                            .param("state", STATE)
                            .param("error", "access_denied")
                            .param("error_description", "denied")
                            .cookie(new Cookie(STATE_COOKIE, STATE)))
                    .andExpect(status().isFound());

            verify(githubOAuthService).handleCallback(null, STATE, STATE, "access_denied", "denied");
        }

        @Test
        @DisplayName("state 쿠키를 서비스로 넘겨 브라우저 결속을 검증하게 한다")
        void forwardsStateCookie() throws Exception {
            given(githubOAuthService.handleCallback(any(), any(), any(), any(), any()))
                    .willReturn(FRONTEND_CALLBACK + "?code=one-time");

            mockMvc.perform(get("/auth/github/callback")
                            .param("code", "gh-code")
                            .param("state", STATE)
                            .cookie(new Cookie(STATE_COOKIE, "cookie-state")))
                    .andExpect(status().isFound());

            verify(githubOAuthService)
                    .handleCallback(eq("gh-code"), eq(STATE), eq("cookie-state"), isNull(), isNull());
        }

        @Test
        @DisplayName("쿠키가 없으면 null로 넘어가 서비스가 거부한다")
        void forwardsNullWhenCookieMissing() throws Exception {
            given(githubOAuthService.handleCallback(any(), any(), any(), any(), any()))
                    .willReturn(FRONTEND_CALLBACK + "?error=" + ErrorCode.GITHUB_OAUTH_STATE_INVALID.getCode());

            mockMvc.perform(get("/auth/github/callback")
                            .param("code", "gh-code")
                            .param("state", STATE))
                    .andExpect(status().isFound());

            verify(githubOAuthService)
                    .handleCallback(eq("gh-code"), eq(STATE), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("성공이든 실패든 state 쿠키를 만료시킨다 — 한 번 쓰면 끝이다")
        void alwaysClearsStateCookie() throws Exception {
            given(githubOAuthService.handleCallback(any(), any(), any(), any(), any()))
                    .willReturn(FRONTEND_CALLBACK + "?code=one-time");

            mockMvc.perform(get("/auth/github/callback")
                            .param("code", "gh-code")
                            .param("state", STATE)
                            .cookie(new Cookie(STATE_COOKIE, STATE)))
                    .andExpect(cookie().value(STATE_COOKIE, ""))
                    .andExpect(cookie().maxAge(STATE_COOKIE, 0));
        }

        @Test
        @DisplayName("콜백 응답은 캐시하지 않는다 — URL에 로그인 코드가 실려 있다")
        void forbidsCaching() throws Exception {
            given(githubOAuthService.handleCallback(any(), any(), any(), any(), any()))
                    .willReturn(FRONTEND_CALLBACK + "?code=one-time");

            mockMvc.perform(get("/auth/github/callback").param("code", "gh-code"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"));
        }
    }

    @Nested
    @DisplayName("연결 해제 — DELETE /auth/github/connection")
    class Disconnect {

        @Test
        @DisplayName("토큰의 userId로 연결을 끊는다")
        void disconnectsAuthenticatedUser() throws Exception {
            mockMvc.perform(delete("/auth/github/connection")
                            .header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk());

            verify(githubConnectionService).disconnect(USER_ID);
        }

        @Test
        @DisplayName("인증 없이는 401이고 서비스를 부르지 않는다")
        void requiresAuthentication() throws Exception {
            mockMvc.perform(delete("/auth/github/connection"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

            verify(githubConnectionService, org.mockito.Mockito.never()).disconnect(any());
        }
    }
}

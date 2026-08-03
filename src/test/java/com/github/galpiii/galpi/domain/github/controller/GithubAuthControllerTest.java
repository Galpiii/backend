package com.github.galpiii.galpi.domain.github.controller;

import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.support.WebMvcTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("GitHub 컨트롤러 — 인증 시작·콜백·연결 해제")
class GithubAuthControllerTest extends WebMvcTestSupport {

    private static final long USER_ID = 7L;
    private static final String FRONTEND_CALLBACK = "https://galpi.dev/auth/callback";

    @Autowired
    private JwtTokenProvider tokenProvider;

    private String bearer() {
        return "Bearer " + tokenProvider.createAccessToken(USER_ID);
    }

    @Nested
    @DisplayName("인증 시작 — GET /auth/github/authorize")
    class Authorize {

        @Test
        @DisplayName("GitHub 인증 페이지로 302 리다이렉트한다")
        void redirectsToGithub() throws Exception {
            given(githubOAuthService.buildAuthorizeRedirect(isNull()))
                    .willReturn("https://github.com/login/oauth/authorize?state=abc");

            mockMvc.perform(get("/auth/github/authorize"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("https://github.com/login/oauth/authorize?state=abc"));
        }

        @Test
        @DisplayName("returnTo를 서비스로 그대로 넘긴다")
        void passesReturnTo() throws Exception {
            given(githubOAuthService.buildAuthorizeRedirect("/projects/3"))
                    .willReturn("https://github.com/login/oauth/authorize?state=abc");

            mockMvc.perform(get("/auth/github/authorize").param("returnTo", "/projects/3"))
                    .andExpect(status().isFound());

            verify(githubOAuthService).buildAuthorizeRedirect("/projects/3");
        }

        @Test
        @DisplayName("리다이렉트 응답을 캐시하지 않는다")
        void forbidsCaching() throws Exception {
            given(githubOAuthService.buildAuthorizeRedirect(any())).willReturn("https://github.com/x");

            mockMvc.perform(get("/auth/github/authorize"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }
    }

    @Nested
    @DisplayName("콜백 — GET /auth/github/callback")
    class Callback {

        @Test
        @DisplayName("로그인 코드를 실은 프론트 URL로 302 리다이렉트한다")
        void redirectsToFrontendWithLoginCode() throws Exception {
            given(githubOAuthService.handleCallback("gh-code", "state", null, null))
                    .willReturn(FRONTEND_CALLBACK + "?code=one-time");

            mockMvc.perform(get("/auth/github/callback")
                            .param("code", "gh-code")
                            .param("state", "state"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl(FRONTEND_CALLBACK + "?code=one-time"));
        }

        @Test
        @DisplayName("실패해도 JSON이 아니라 프론트 오류 화면으로 302한다")
        void redirectsInsteadOfReturningJson() throws Exception {
            given(githubOAuthService.handleCallback(any(), any(), any(), any()))
                    .willReturn(FRONTEND_CALLBACK + "?error=" + ErrorCode.GITHUB_OAUTH_STATE_INVALID.getCode());

            mockMvc.perform(get("/auth/github/callback").param("code", "gh-code"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl(
                            FRONTEND_CALLBACK + "?error=" + ErrorCode.GITHUB_OAUTH_STATE_INVALID.getCode()));
        }

        @Test
        @DisplayName("GitHub이 준 error 파라미터를 서비스로 넘긴다")
        void forwardsGithubError() throws Exception {
            given(githubOAuthService.handleCallback(isNull(), any(), any(), any()))
                    .willReturn(FRONTEND_CALLBACK + "?error=GITHUB-004");

            mockMvc.perform(get("/auth/github/callback")
                            .param("state", "state")
                            .param("error", "access_denied")
                            .param("error_description", "denied"))
                    .andExpect(status().isFound());

            verify(githubOAuthService).handleCallback(null, "state", "access_denied", "denied");
        }

        @Test
        @DisplayName("콜백 응답은 캐시하지 않는다 — URL에 로그인 코드가 실려 있다")
        void forbidsCaching() throws Exception {
            given(githubOAuthService.handleCallback(any(), any(), any(), any()))
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

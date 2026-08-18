package com.github.galpiii.galpi.domain.github.controller;

import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.github.dto.InstallUrlResponse;
import com.github.galpiii.galpi.domain.github.exception.GithubRateLimitedException;
import com.github.galpiii.galpi.support.WebMvcTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("GitHub 설치 컨트롤러 — install-url·setup 콜백")
class GithubSetupControllerTest extends WebMvcTestSupport {

    private static final long USER_ID = 7L;
    private static final String INSTALL_URL =
            "https://github.com/apps/galpi-app/installations/new?state=abc";
    private static final String FRONT_REDIRECT =
            "https://galpi.dev/auth/callback?installation=verified&returnTo=%2Fprojects%2F3";

    @Autowired
    private JwtTokenProvider tokenProvider;

    private String bearer() {
        return "Bearer " + tokenProvider.createAccessToken(USER_ID);
    }

    @Nested
    @DisplayName("설치 URL — POST /github/install-url")
    class InstallUrl {

        @Test
        @DisplayName("인증된 사용자에게 설치 URL을 돌려준다")
        void returnsInstallUrl() throws Exception {
            given(githubSetupService.buildInstallUrl(eq(USER_ID), any()))
                    .willReturn(new InstallUrlResponse(INSTALL_URL));

            mockMvc.perform(post("/github/install-url")
                            .param("returnTo", "/projects/3/repositories")
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.installUrl").value(INSTALL_URL))
                    .andExpect(header().string("Cache-Control", "no-store"));

            verify(githubSetupService).buildInstallUrl(USER_ID, "/projects/3/repositories");
        }

        @Test
        @DisplayName("토큰 없이 부르면 401이다")
        void requiresAuthentication() throws Exception {
            mockMvc.perform(post("/github/install-url"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("일회성 state를 만드는 GET 요청은 허용하지 않는다")
        void rejectsGet() throws Exception {
            mockMvc.perform(get("/github/install-url")
                            .header("Authorization", bearer()))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    @Nested
    @DisplayName("저장소 선택 — GET /github/repositories")
    class Repositories {

        @Test
        @DisplayName("읽기 전용 응답은 GET으로 제공하고 캐시하지 않는다")
        void isGetAndNotCached() throws Exception {
            given(githubInstallationService.listRepositories(USER_ID, 3L))
                    .willReturn(List.of());

            mockMvc.perform(get("/github/repositories")
                            .param("projectId", "3")
                            .header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"));

            verify(githubInstallationService).listRepositories(USER_ID, 3L);
        }

        @Test
        @DisplayName("읽기 API에 POST 요청은 허용하지 않는다")
        void rejectsPost() throws Exception {
            mockMvc.perform(post("/github/repositories")
                            .header("Authorization", bearer()))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("GitHub rate limit의 Retry-After를 프론트에 전달한다")
        void forwardsRetryAfter() throws Exception {
            given(githubInstallationService.listRepositories(USER_ID, null))
                    .willThrow(new GithubRateLimitedException(17));

            mockMvc.perform(get("/github/repositories")
                            .header("Authorization", bearer()))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "17"));
        }
    }

    @Nested
    @DisplayName("setup 콜백 — GET /auth/github/setup/callback")
    class SetupCallback {

        @Test
        @DisplayName("Authorization 헤더 없이도 열려 있다 — GitHub이 브라우저를 직접 보낸다")
        void isPublic() throws Exception {
            given(githubSetupService.handleSetupCallback(any(), any(), any()))
                    .willReturn(FRONT_REDIRECT);

            mockMvc.perform(get("/auth/github/setup/callback")
                            .param("installation_id", "4242")
                            .param("setup_action", "install")
                            .param("state", "abc"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl(FRONT_REDIRECT));
        }

        @Test
        @DisplayName("installation_id·setup_action·state를 서비스로 넘긴다")
        void passesAllInputs() throws Exception {
            given(githubSetupService.handleSetupCallback(any(), any(), any()))
                    .willReturn(FRONT_REDIRECT);

            mockMvc.perform(get("/auth/github/setup/callback")
                            .param("installation_id", "4242")
                            .param("setup_action", "install")
                            .param("state", "abc"))
                    .andExpect(status().isFound());

            verify(githubSetupService).handleSetupCallback(4242L, "install", "abc");
        }

        @Test
        @DisplayName("세션 쿠키가 있어도 판단에 쓰지 않는다")
        void ignoresSessionCookie() throws Exception {
            given(githubSetupService.handleSetupCallback(any(), any(), isNull()))
                    .willReturn("https://galpi.dev/auth/callback?error=GITHUB-008");

            mockMvc.perform(get("/auth/github/setup/callback")
                            .param("installation_id", "4242")
                            .param("setup_action", "install")
                            .cookie(new Cookie("galpi_refresh", "refresh-value")))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("https://galpi.dev/auth/callback?error=GITHUB-008"));

            verify(githubSetupService).handleSetupCallback(4242L, "install", null);
        }

        @Test
        @DisplayName("파라미터가 하나도 없어도 500이 아니라 서비스 판단에 맡긴다")
        void toleratesMissingParameters() throws Exception {
            given(githubSetupService.handleSetupCallback(isNull(), isNull(), isNull()))
                    .willReturn("https://galpi.dev/auth/callback?error=GITHUB-008");

            mockMvc.perform(get("/auth/github/setup/callback"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("https://galpi.dev/auth/callback?error=GITHUB-008"));
        }

        @Test
        @DisplayName("콜백 응답은 캐시하지 않는다")
        void isNotCached() throws Exception {
            given(githubSetupService.handleSetupCallback(any(), any(), any()))
                    .willReturn(FRONT_REDIRECT);

            mockMvc.perform(get("/auth/github/setup/callback").param("state", "abc"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }
}

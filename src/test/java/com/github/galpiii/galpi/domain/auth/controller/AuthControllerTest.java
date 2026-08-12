package com.github.galpiii.galpi.domain.auth.controller;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.auth.dto.IssuedTokens;
import com.github.galpiii.galpi.domain.auth.dto.MeResponse;
import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.auth.support.CookieAuthCsrfFilter;
import com.github.galpiii.galpi.domain.user.entity.GithubConnectionStatus;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import com.github.galpiii.galpi.support.WebMvcTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "galpi.jwt.cookie.secure=true")
@DisplayName("AuthController — 세션 발급과 Refresh 쿠키")
class AuthControllerTest extends WebMvcTestSupport {

    private static final long USER_ID = 7L;
    private static final String REFRESH_COOKIE = "galpi_refresh";

    @Autowired
    private JwtTokenProvider tokenProvider;
    @Autowired
    private JwtProperties jwtProperties;

    private String setCookieOf(MvcResult result) {
        return result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    }

    private String bearer() {
        return "Bearer " + tokenProvider.createAccessToken(USER_ID);
    }

    @Nested
    @DisplayName("로그인 코드 교환 — POST /auth/token")
    class Exchange {

        @Test
        @DisplayName("Access 토큰은 본문으로, Refresh는 쿠키로 내려준다")
        void returnsAccessInBodyAndRefreshInCookie() throws Exception {
            given(authService.exchangeLoginCode("login-code"))
                    .willReturn(new IssuedTokens("access-jwt", "refresh-jwt", 1800L));

            MvcResult result = mockMvc.perform(post("/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"login-code\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").value("access-jwt"))
                    .andExpect(jsonPath("$.data.expiresIn").value(1800))
                    .andReturn();

            assertThat(result.getResponse().getContentAsString()).doesNotContain("refresh-jwt");
            assertThat(setCookieOf(result)).contains(REFRESH_COOKIE + "=refresh-jwt");
        }

        @Test
        @DisplayName("Refresh 쿠키는 HttpOnly·Secure·SameSite·Path를 모두 갖춘다")
        void setsHardenedCookieAttributes() throws Exception {
            given(authService.exchangeLoginCode(any()))
                    .willReturn(new IssuedTokens("access-jwt", "refresh-jwt", 1800L));

            MvcResult result = mockMvc.perform(post("/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"login-code\"}"))
                    .andReturn();

            assertThat(setCookieOf(result))
                    .contains("HttpOnly")
                    .contains("Secure")
                    .contains("SameSite=Lax")
                    .contains("Path=" + jwtProperties.cookie().path());
        }

        @Test
        @DisplayName("토큰 응답은 캐시하지 않는다")
        void forbidsCaching() throws Exception {
            given(authService.exchangeLoginCode(any()))
                    .willReturn(new IssuedTokens("access-jwt", "refresh-jwt", 1800L));

            mockMvc.perform(post("/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"login-code\"}"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }

        @Test
        @DisplayName("code가 비면 400이다")
        void rejectsBlankCode() throws Exception {
            mockMvc.perform(post("/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"  \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));
        }
    }

    @Nested
    @DisplayName("재발급 — POST /auth/refresh")
    class Refresh {

        @Test
        @DisplayName("쿠키에서 refresh를 읽어 새 쿠키로 교체한다")
        void rotatesCookie() throws Exception {
            given(authService.refresh("old-refresh"))
                    .willReturn(new IssuedTokens("new-access", "new-refresh", 1800L));

            MvcResult result = mockMvc.perform(post("/auth/refresh").header(CookieAuthCsrfFilter.HEADER, "1")
                            .cookie(new Cookie(REFRESH_COOKIE, "old-refresh")))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(setCookieOf(result)).contains(REFRESH_COOKIE + "=new-refresh");
        }

        @Test
        @DisplayName("쿠키가 없으면 서비스가 null을 받고 401이 된다")
        void rejectsMissingCookie() throws Exception {
            willThrow(new UnauthorizedException(ErrorCode.REFRESH_TOKEN_NOT_FOUND))
                    .given(authService).refresh(null);

            mockMvc.perform(post("/auth/refresh").header(CookieAuthCsrfFilter.HEADER, "1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.REFRESH_TOKEN_NOT_FOUND.getCode()));
        }

        @Test
        @DisplayName("재사용이 감지되면 AUTH-008로 알린다")
        void reportsReuse() throws Exception {
            willThrow(new UnauthorizedException(ErrorCode.REFRESH_TOKEN_REUSED))
                    .given(authService).refresh("stolen");

            mockMvc.perform(post("/auth/refresh").header(CookieAuthCsrfFilter.HEADER, "1")
                            .cookie(new Cookie(REFRESH_COOKIE, "stolen")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.REFRESH_TOKEN_REUSED.getCode()));
        }
    }

    @Nested
    @DisplayName("로그아웃 — POST /auth/logout")
    class Logout {

        @Test
        @DisplayName("쿠키를 즉시 만료시킨다")
        void expiresCookie() throws Exception {
            MvcResult result = mockMvc.perform(post("/auth/logout").header(CookieAuthCsrfFilter.HEADER, "1")
                            .cookie(new Cookie(REFRESH_COOKIE, "refresh-jwt")))
                    .andExpect(status().isOk())
                    .andReturn();

            verify(authService).logout("refresh-jwt");
            assertThat(setCookieOf(result)).contains("Max-Age=0");
        }

        @Test
        @DisplayName("쿠키가 없어도 200으로 끝낸다")
        void succeedsWithoutCookie() throws Exception {
            mockMvc.perform(post("/auth/logout").header(CookieAuthCsrfFilter.HEADER, "1")).andExpect(status().isOk());

            verify(authService).logout(null);
        }
    }

    @Nested
    @DisplayName("내 정보 — GET /auth/me")
    class Me {

        @Test
        @DisplayName("Bearer 토큰의 userId로 조회한다")
        void resolvesUserIdFromToken() throws Exception {
            given(authService.getMe(USER_ID)).willReturn(new MeResponse(
                    USER_ID, "octocat", "Octo", "dev@galpi.dev", "https://avatars/1",
                    new MeResponse.Github(1L, GithubConnectionStatus.CONNECTED, true)));

            mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(USER_ID))
                    .andExpect(jsonPath("$.data.github.githubTokenValid").value(true));

            verify(authService).getMe(USER_ID);
        }

        @Test
        @DisplayName("GitHub 토큰이 만료돼도 200으로 응답한다")
        void staysUsableWithExpiredGithubToken() throws Exception {
            given(authService.getMe(USER_ID)).willReturn(new MeResponse(
                    USER_ID, "octocat", "Octo", null, "https://avatars/1",
                    new MeResponse.Github(1L, GithubConnectionStatus.DISCONNECTED, false)));

            mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.github.githubTokenValid").value(false));
        }

        @Test
        @DisplayName("토큰이 없으면 401이다")
        void requiresAuthentication() throws Exception {
            mockMvc.perform(get("/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        }
    }
}

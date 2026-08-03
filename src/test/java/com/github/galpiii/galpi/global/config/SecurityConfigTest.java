package com.github.galpiii.galpi.global.config;

import com.github.galpiii.galpi.domain.auth.dto.IssuedTokens;
import com.github.galpiii.galpi.domain.github.dto.AuthorizeRedirect;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.support.WebMvcTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SecurityConfig — 공개 엔드포인트 화이트리스트")
class SecurityConfigTest extends WebMvcTestSupport {

    private static final IssuedTokens TOKENS = new IssuedTokens("access", "refresh", 1800L);

    @Nested
    @DisplayName("인증 없이 열려 있어야 하는 곳")
    class PublicEndpoints {

        @Test
        @DisplayName("GitHub 인증 시작은 열려 있다")
        void authorizeIsPublic() throws Exception {
            given(githubOAuthService.buildAuthorizeRedirect(any()))
                    .willReturn(new AuthorizeRedirect("https://github.com/x", "state"));

            mockMvc.perform(get("/auth/github/authorize")).andExpect(status().isFound());
        }

        @Test
        @DisplayName("GitHub 콜백은 열려 있다")
        void callbackIsPublic() throws Exception {
            given(githubOAuthService.handleCallback(any(), any(), any(), any(), any()))
                    .willReturn("https://galpi.dev/auth/callback?code=x");

            mockMvc.perform(get("/auth/github/callback").param("code", "c"))
                    .andExpect(status().isFound());
        }

        @Test
        @DisplayName("로그인 코드 교환은 열려 있다 — 아직 Access 토큰이 없다")
        void tokenExchangeIsPublic() throws Exception {
            given(authService.exchangeLoginCode(any())).willReturn(TOKENS);

            mockMvc.perform(post("/auth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"x\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("재발급은 열려 있다 — Access 토큰이 만료된 상태로 부른다")
        void refreshIsPublic() throws Exception {
            given(authService.refresh(any())).willReturn(TOKENS);

            mockMvc.perform(post("/auth/refresh")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("로그아웃은 열려 있다")
        void logoutIsPublic() throws Exception {
            mockMvc.perform(post("/auth/logout")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("CORS 프리플라이트는 인증을 요구하지 않는다")
        void preflightIsPublic() throws Exception {
            mockMvc.perform(options("/auth/me")
                            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(
                            HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        }
    }

    @Nested
    @DisplayName("인증이 필요한 곳")
    class ProtectedEndpoints {

        @ParameterizedTest(name = "GET {0} 은 401이다")
        @ValueSource(strings = {"/auth/me", "/unknown/path"})
        @DisplayName("화이트리스트 밖의 GET은 인증을 요구한다")
        void requiresAuthenticationForGet(String path) throws Exception {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        }

        @Test
        @DisplayName("GitHub 연결 해제는 인증을 요구한다")
        void requiresAuthenticationForDisconnect() throws Exception {
            mockMvc.perform(delete("/auth/github/connection"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("401 응답도 공통 에러 포맷을 따른다")
        void usesCommonErrorFormat() throws Exception {
            mockMvc.perform(get("/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHORIZED.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }
}

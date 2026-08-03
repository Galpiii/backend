package com.github.galpiii.galpi.domain.auth.jwt;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("JwtAuthenticationFilter — Bearer 토큰 인증")
class JwtAuthenticationFilterTest {

    private static final long USER_ID = 7L;

    private JwtTokenProvider tokenProvider;
    private JwtAuthenticationFilter filter;
    private FilterChain chain;

    private static JwtProperties properties() {
        return new JwtProperties(
                "galpi-test-secret-key-must-be-at-least-32-bytes", "galpi",
                Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofSeconds(60),
                new JwtProperties.Cookie("galpi_refresh", "/auth", true, "Lax", ""));
    }

    private MockHttpServletResponse doFilter(String authorizationHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/me");
        if (authorizationHeader != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(properties());
        filter = new JwtAuthenticationFilter(tokenProvider);
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("유효한 Access 토큰이면 userId를 principal로 심는다")
    void authenticatesValidAccessToken() throws Exception {
        doFilter("Bearer " + tokenProvider.createAccessToken(USER_ID));

        assertThat(currentAuthentication()).isNotNull();
        assertThat(currentAuthentication().getPrincipal())
                .isEqualTo(new AuthPrincipal(USER_ID));
        assertThat(currentAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("Refresh 토큰을 Access 자리에 넣으면 인증하지 않는다")
    void rejectsRefreshTokenAsAccessToken() throws Exception {
        doFilter("Bearer " + tokenProvider.createRefreshToken(USER_ID));

        assertThat(currentAuthentication()).isNull();
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 인증하지 않는다")
    void rejectsForeignSignature() throws Exception {
        JwtTokenProvider attacker = new JwtTokenProvider(new JwtProperties(
                "attacker-secret-key-that-is-also-32-bytes-long", "galpi",
                Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofSeconds(60),
                new JwtProperties.Cookie("galpi_refresh", "/auth", true, "Lax", "")));

        doFilter("Bearer " + attacker.createAccessToken(USER_ID));

        assertThat(currentAuthentication()).isNull();
    }

    @ParameterizedTest(name = "\"{0}\" 헤더는 인증하지 않는다")
    @ValueSource(strings = {"Bearer ", "Bearer    ", "Basic dXNlcjpwYXNz", "token abc", "abc"})
    @DisplayName("Bearer 형식이 아니거나 비어 있으면 인증하지 않는다")
    void ignoresMalformedHeader(String header) throws Exception {
        doFilter(header);

        assertThat(currentAuthentication()).isNull();
    }

    @Test
    @DisplayName("헤더가 없어도 체인을 그대로 통과시킨다")
    void passesThroughWithoutHeader() throws Exception {
        doFilter(null);

        assertThat(currentAuthentication()).isNull();
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("인증에 실패해도 체인을 끊지 않는다 — 응답은 EntryPoint가 만든다")
    void continuesChainOnFailure() throws Exception {
        MockHttpServletResponse response = doFilter("Bearer not-a-jwt");

        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("실패 시 앞선 요청의 인증이 남지 않도록 컨텍스트를 비운다")
    void clearsStaleContextOnFailure() throws Exception {
        doFilter("Bearer " + tokenProvider.createAccessToken(USER_ID));
        assertThat(currentAuthentication()).isNotNull();

        doFilter("Bearer not-a-jwt");

        assertThat(currentAuthentication()).isNull();
    }
}

package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.auth.store.LoginCodeStore;
import com.github.galpiii.galpi.domain.auth.support.RedirectUriValidator;
import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.client.GithubOAuthClient;
import com.github.galpiii.galpi.domain.github.client.dto.GithubAccessTokenResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.dto.AuthorizeRedirect;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.store.OAuthCodeGuard;
import com.github.galpiii.galpi.domain.github.store.OAuthStateStore;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GithubOAuthService — OAuth 콜백")
class GithubOAuthServiceTest {

    private static final String CODE = "gh-oauth-code";
    private static final String STATE = "random-state-value";
    private static final String USER_TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz012345";
    private static final Duration SESSION_TTL = Duration.ofDays(14);

    @Mock
    private GithubOAuthClient oAuthClient;
    @Mock
    private GithubApiClient apiClient;
    @Mock
    private GithubUserService githubUserService;
    @Mock
    private GithubUserTokenService userTokenService;
    @Mock
    private OAuthStateStore stateStore;
    @Mock
    private OAuthCodeGuard codeGuard;
    @Mock
    private LoginCodeStore loginCodeStore;

    private GithubTokenExpiryMonitor expiryMonitor;
    private GithubOAuthService service;

    private static GithubAppProperties githubProperties() {
        return new GithubAppProperties(
                "12345", "galpi-app", "Iv1.client", "client-secret", "-----BEGIN PRIVATE KEY-----\nx\n-----END PRIVATE KEY-----",
                "https://api.galpi.dev", "2022-11-28", "https://api.github.com", "https://github.com",
                "Galpi", List.of("https://galpi.dev"), "https://galpi.dev/auth/callback",
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2, 10);
    }

    private static JwtProperties jwtProperties() {
        return new JwtProperties(
                "galpi-test-secret-key-must-be-at-least-32-bytes", "galpi",
                Duration.ofMinutes(30), SESSION_TTL, Duration.ofDays(90), Duration.ofSeconds(60),
                new JwtProperties.Cookie("galpi_refresh", "/auth", true, "Lax", ""));
    }

    private static GithubAccessTokenResponse tokenResponse(Long expiresIn) {
        return new GithubAccessTokenResponse(
                USER_TOKEN, "bearer", expiresIn, "ghr_refreshtokenvalue", 15897600L, null, null);
    }

    private static User userWithId(long id) {
        User user = User.ofGithub(1L, "octocat", "Octo", "dev@galpi.dev", "https://avatars/1");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static String errorUrl(ErrorCode errorCode) {
        return "https://galpi.dev/auth/callback?error=" + errorCode.getCode();
    }

    @BeforeEach
    void setUp() {
        GithubAppProperties githubProperties = githubProperties();
        expiryMonitor = new GithubTokenExpiryMonitor();
        service = new GithubOAuthService(
                oAuthClient, apiClient, githubUserService, userTokenService, expiryMonitor,
                stateStore, codeGuard, loginCodeStore,
                new RedirectUriValidator(githubProperties), jwtProperties());
    }

    private String callback(String code, String state, String error, String errorDescription) {
        return service.handleCallback(code, state, state, error, errorDescription);
    }

    private void givenHappyPath() {
        given(stateStore.consume(STATE)).willReturn(Optional.of(""));
        given(codeGuard.markUsed(CODE)).willReturn(true);
        given(oAuthClient.exchangeCodeForToken(CODE)).willReturn(tokenResponse(28800L));
        given(apiClient.getAuthenticatedUser(USER_TOKEN))
                .willReturn(new GithubUserResponse(1L, "octocat", "https://avatars/1", null, "Octo"));
        given(githubUserService.upsert(any())).willReturn(userWithId(7L));
        given(loginCodeStore.issue(eq(7L), any())).willReturn("one-time-login-code");
    }

    @Nested
    @DisplayName("정상 경로")
    class HappyPath {

        @Test
        @DisplayName("일회용 로그인 코드만 실어 프론트로 리다이렉트한다")
        void redirectsWithLoginCodeOnly() {
            givenHappyPath();

            String redirect = callback(CODE, STATE, null, null);

            assertThat(redirect).startsWith("https://galpi.dev/auth/callback?code=one-time-login-code");
        }

        @Test
        @DisplayName("Access JWT를 리다이렉트 URL에 담지 않는다")
        void neverPutsAccessTokenInUrl() {
            givenHappyPath();

            String redirect = callback(CODE, STATE, null, null);

            assertThat(redirect)
                    .doesNotContain(USER_TOKEN)
                    .doesNotContain("access_token")
                    .doesNotContain("eyJ");
        }

        @Test
        @DisplayName("발급받은 토큰과 GitHub expires_in을 토큰 서비스에 그대로 넘긴다")
        void handsTokenToTokenService() {
            givenHappyPath();

            callback(CODE, STATE, null, null);

            ArgumentCaptor<Duration> expiresIn = ArgumentCaptor.forClass(Duration.class);
            verify(userTokenService).save(any(User.class), eq(USER_TOKEN), expiresIn.capture());
            assertThat(expiresIn.getValue()).isEqualTo(Duration.ofSeconds(28800));
        }

        @Test
        @DisplayName("refresh token은 어디에도 넘기지 않는다")
        void discardsRefreshToken() {
            givenHappyPath();

            callback(CODE, STATE, null, null);

            verify(userTokenService).save(any(User.class), eq(USER_TOKEN), any());
            verify(userTokenService, never()).save(any(), eq("ghr_refreshtokenvalue"), any());
        }

        @Test
        @DisplayName("복귀 경로가 있으면 returnTo로 함께 넘긴다")
        void preservesReturnPath() {
            givenHappyPath();
            given(stateStore.consume(STATE)).willReturn(Optional.of("/projects/3"));

            String redirect = callback(CODE, STATE, null, null);

            assertThat(redirect).contains("returnTo=%2Fprojects%2F3");
        }
    }

    @Nested
    @DisplayName("state 검증")
    class StateValidation {

        @Test
        @DisplayName("state 없이 콜백을 호출하면 오류 화면으로 넘긴다")
        void rejectsMissingState() {
            given(stateStore.consume(null)).willReturn(Optional.empty());

            String redirect = callback(CODE, null, null, null);

            assertThat(redirect).isEqualTo(errorUrl(ErrorCode.GITHUB_OAUTH_STATE_INVALID));
        }

        @Test
        @DisplayName("이미 사용된 state는 두 번째 호출에서 거부한다")
        void rejectsReusedState() {
            given(stateStore.consume(STATE)).willReturn(Optional.of("")).willReturn(Optional.empty());
            given(codeGuard.markUsed(CODE)).willReturn(true);
            given(oAuthClient.exchangeCodeForToken(CODE)).willReturn(tokenResponse(28800L));
            given(apiClient.getAuthenticatedUser(USER_TOKEN))
                    .willReturn(new GithubUserResponse(1L, "octocat", "https://avatars/1", null, "Octo"));
            given(githubUserService.upsert(any())).willReturn(userWithId(7L));
            given(loginCodeStore.issue(anyLong(), any())).willReturn("code");

            callback(CODE, STATE, null, null);

            assertThat(callback(CODE, STATE, null, null))
                    .isEqualTo(errorUrl(ErrorCode.GITHUB_OAUTH_STATE_INVALID));
        }

        @Test
        @DisplayName("state가 유효하지 않으면 토큰 교환을 시도하지 않는다")
        void doesNotExchangeCodeWhenStateInvalid() {
            given(stateStore.consume(STATE)).willReturn(Optional.empty());

            callback(CODE, STATE, null, null);

            verify(oAuthClient, never()).exchangeCodeForToken(any());
        }
    }

    @Nested
    @DisplayName("code 재사용 차단")
    class CodeReuse {

        @Test
        @DisplayName("이미 사용된 code면 오류 화면으로 넘긴다")
        void rejectsReusedCode() {
            given(stateStore.consume(STATE)).willReturn(Optional.of(""));
            given(codeGuard.markUsed(CODE)).willReturn(false);

            String redirect = callback(CODE, STATE, null, null);

            assertThat(redirect).isEqualTo(errorUrl(ErrorCode.GITHUB_OAUTH_CODE_REUSED));
            verify(oAuthClient, never()).exchangeCodeForToken(any());
        }

        @Test
        @DisplayName("code가 비어 있으면 오류 화면으로 넘긴다")
        void rejectsBlankCode() {
            given(stateStore.consume(STATE)).willReturn(Optional.of(""));

            assertThat(callback("  ", STATE, null, null))
                    .isEqualTo(errorUrl(ErrorCode.GITHUB_OAUTH_FAILED));
        }
    }

    @Nested
    @DisplayName("실패해도 브라우저에 JSON을 노출하지 않는다")
    class NeverThrowsToBrowser {

        @Test
        @DisplayName("토큰 교환 실패는 오류 화면으로 넘긴다")
        void redirectsWhenTokenExchangeFails() {
            given(stateStore.consume(STATE)).willReturn(Optional.of(""));
            given(codeGuard.markUsed(CODE)).willReturn(true);
            given(oAuthClient.exchangeCodeForToken(CODE))
                    .willThrow(new UnauthorizedException(ErrorCode.GITHUB_OAUTH_FAILED));

            assertThat(callback(CODE, STATE, null, null))
                    .isEqualTo(errorUrl(ErrorCode.GITHUB_OAUTH_FAILED));
        }

        @Test
        @DisplayName("GitHub API 오류도 오류 화면으로 넘긴다")
        void redirectsWhenUserLookupFails() {
            given(stateStore.consume(STATE)).willReturn(Optional.of(""));
            given(codeGuard.markUsed(CODE)).willReturn(true);
            given(oAuthClient.exchangeCodeForToken(CODE)).willReturn(tokenResponse(28800L));
            given(apiClient.getAuthenticatedUser(USER_TOKEN)).willThrow(new GithubApiException());

            assertThat(callback(CODE, STATE, null, null))
                    .isEqualTo(errorUrl(ErrorCode.GITHUB_API_ERROR));
        }

        @Test
        @DisplayName("예상치 못한 런타임 오류도 오류 화면으로 넘긴다")
        void redirectsOnUnexpectedFailure() {
            given(stateStore.consume(STATE)).willReturn(Optional.of(""));
            given(codeGuard.markUsed(CODE)).willReturn(true);
            given(oAuthClient.exchangeCodeForToken(CODE))
                    .willThrow(new IllegalStateException("boom"));

            assertThat(callback(CODE, STATE, null, null))
                    .isEqualTo(errorUrl(ErrorCode.INTERNAL_SERVER_ERROR));
        }

        @Test
        @DisplayName("실패해도 알아낸 복귀 경로는 유지한다")
        void preservesReturnPathOnFailure() {
            given(stateStore.consume(STATE)).willReturn(Optional.of("/projects/3"));
            given(codeGuard.markUsed(CODE)).willReturn(false);

            assertThat(callback(CODE, STATE, null, null))
                    .contains("error=" + ErrorCode.GITHUB_OAUTH_CODE_REUSED.getCode())
                    .contains("returnTo=%2Fprojects%2F3");
        }
    }

    @Nested
    @DisplayName("오류 경로")
    class ErrorPath {

        @Test
        @DisplayName("GitHub이 error를 주면 프론트 오류 화면으로 넘긴다")
        void redirectsToErrorPage() {
            given(stateStore.consume(STATE)).willReturn(Optional.of(""));

            String redirect = callback(
                    null, STATE, "access_denied", "The user has denied your application access.");

            assertThat(redirect)
                    .startsWith("https://galpi.dev/auth/callback?error=")
                    .contains(ErrorCode.GITHUB_OAUTH_FAILED.getCode());
        }

        @Test
        @DisplayName("error 경로에서도 남은 state를 정리한다")
        void consumesStateOnError() {
            given(stateStore.consume(STATE)).willReturn(Optional.of(""));

            callback(null, STATE, "access_denied", null);

            verify(stateStore).consume(STATE);
        }

        @Test
        @DisplayName("복귀 경로를 알 수 있으면 오류 화면에도 returnTo를 실어준다")
        void preservesReturnPathOnError() {
            given(stateStore.consume(STATE)).willReturn(Optional.of("/projects/3"));

            String redirect = callback(null, STATE, "access_denied", null);

            assertThat(redirect).contains("returnTo=%2Fprojects%2F3");
        }

        @Test
        @DisplayName("state가 유효하지 않아도 error 응답은 오류 화면으로 넘긴다")
        void doesNotRejectOnUnknownState() {
            given(stateStore.consume(STATE)).willReturn(Optional.empty());

            String redirect = callback(null, STATE, "access_denied", null);

            assertThat(redirect)
                    .startsWith("https://galpi.dev/auth/callback?error=")
                    .doesNotContain("returnTo");
        }
    }

    @Nested
    @DisplayName("토큰 만료 설정 감지")
    class ExpirySetting {

        @Test
        @DisplayName("expires_in이 없으면 만료 비활성으로 표시한다")
        void flagsMissingExpiresIn() {
            givenHappyPath();
            given(oAuthClient.exchangeCodeForToken(CODE))
                    .willReturn(new GithubAccessTokenResponse(
                            USER_TOKEN, "bearer", null, null, null, null, null));

            callback(CODE, STATE, null, null);

            assertThat(expiryMonitor.isExpirationDisabled()).isTrue();
        }

        @Test
        @DisplayName("expires_in이 없으면 만료 시각 없음(null)으로 넘긴다")
        void passesNullExpiry() {
            givenHappyPath();
            given(oAuthClient.exchangeCodeForToken(CODE))
                    .willReturn(new GithubAccessTokenResponse(
                            USER_TOKEN, "bearer", null, null, null, null, null));

            callback(CODE, STATE, null, null);

            verify(userTokenService).save(any(User.class), eq(USER_TOKEN), eq((Duration) null));
        }

        @Test
        @DisplayName("expires_in이 있으면 만료 비활성 플래그가 서지 않는다")
        void doesNotFlagWhenExpiryPresent() {
            givenHappyPath();

            callback(CODE, STATE, null, null);

            assertThat(expiryMonitor.isExpirationDisabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("인증 시작")
    class Authorize {

        @Test
        @DisplayName("state를 발급해 authorize URL을 만든다")
        void issuesStateAndBuildsUrl() {
            given(stateStore.issue("/projects")).willReturn(STATE);
            given(oAuthClient.buildAuthorizeUrl(STATE)).willReturn("https://github.com/login/oauth/authorize?state=" + STATE);

            AuthorizeRedirect redirect = service.buildAuthorizeRedirect("/projects");

            assertThat(redirect.url()).contains(STATE);
            assertThat(redirect.state()).isEqualTo(STATE);
            verify(stateStore).issue("/projects");
        }

        @Test
        @DisplayName("허용되지 않은 복귀 대상은 오류 화면으로 넘기고 state를 발급하지 않는다")
        void rejectsOpenRedirect() {
            AuthorizeRedirect redirect = service.buildAuthorizeRedirect("https://evil.example.com/steal");

            assertThat(redirect.url()).isEqualTo(errorUrl(ErrorCode.GITHUB_REDIRECT_NOT_ALLOWED));
            verify(stateStore, never()).issue(any());
        }

        @Test
        @DisplayName("GitHub까지 가지 않는 오류 경로에는 심을 state가 없다")
        void carriesNoStateOnErrorPath() {
            AuthorizeRedirect redirect = service.buildAuthorizeRedirect("https://evil.example.com/steal");

            assertThat(redirect.hasState()).isFalse();
        }

        @Test
        @DisplayName("거부된 복귀 대상은 오류 화면에도 싣지 않는다")
        void doesNotEchoRejectedTarget() {
            AuthorizeRedirect redirect = service.buildAuthorizeRedirect("https://evil.example.com/steal");

            assertThat(redirect.url())
                    .doesNotContain("returnTo")
                    .doesNotContain("evil.example.com");
        }
    }

    @Nested
    @DisplayName("state의 브라우저 결속")
    class BrowserBinding {

        private static final String ATTACKER_STATE = "attacker-issued-state";

        @Test
        @DisplayName("쿼리 state가 브라우저 쿠키와 다르면 거부한다")
        void rejectsStateFromAnotherBrowser() {
            String redirect = service.handleCallback(CODE, ATTACKER_STATE, STATE, null, null);

            assertThat(redirect).isEqualTo(errorUrl(ErrorCode.GITHUB_OAUTH_STATE_INVALID));
            verify(oAuthClient, never()).exchangeCodeForToken(any());
        }

        @Test
        @DisplayName("state 쿠키가 아예 없으면 거부한다")
        void rejectsCallbackWithoutStateCookie() {
            String redirect = service.handleCallback(CODE, STATE, null, null, null);

            assertThat(redirect).isEqualTo(errorUrl(ErrorCode.GITHUB_OAUTH_STATE_INVALID));
            verify(oAuthClient, never()).exchangeCodeForToken(any());
        }

        @Test
        @DisplayName("결속이 깨지면 로그인 코드를 발급하지 않는다")
        void issuesNoLoginCodeWhenBindingBroken() {
            service.handleCallback(CODE, ATTACKER_STATE, STATE, null, null);

            verify(loginCodeStore, never()).issue(anyLong(), any());
        }

        @Test
        @DisplayName("결속이 깨진 콜백은 저장된 state를 소진하지 않는다 — 정상 흐름을 밖에서 깨뜨릴 수 없다")
        void leavesStoredStateIntact() {
            service.handleCallback(CODE, ATTACKER_STATE, STATE, null, null);

            verify(stateStore, never()).consume(any());
        }

        @Test
        @DisplayName("쿼리 state와 쿠키가 같으면 평소대로 진행한다")
        void acceptsMatchingState() {
            givenHappyPath();

            String redirect = service.handleCallback(CODE, STATE, STATE, null, null);

            assertThat(redirect).startsWith("https://galpi.dev/auth/callback?code=one-time-login-code");
        }
    }
}

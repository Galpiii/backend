package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.auth.store.LoginCodeStore;
import com.github.galpiii.galpi.domain.auth.support.RedirectUriValidator;
import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.client.GithubOAuthClient;
import com.github.galpiii.galpi.domain.github.client.dto.GithubAccessTokenResponse;
import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.store.OAuthCodeGuard;
import com.github.galpiii.galpi.domain.github.store.OAuthStateStore;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                "12345", "Iv1.client", "client-secret", "-----BEGIN PRIVATE KEY-----\nx\n-----END PRIVATE KEY-----",
                "https://api.galpi.dev", "2022-11-28", "https://api.github.com", "https://github.com",
                "Galpi", List.of("https://galpi.dev"), "https://galpi.dev/auth/callback",
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2, 10);
    }

    private static JwtProperties jwtProperties() {
        return new JwtProperties(
                "galpi-test-secret-key-must-be-at-least-32-bytes", "galpi",
                Duration.ofMinutes(30), SESSION_TTL, Duration.ofSeconds(60),
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

    @BeforeEach
    void setUp() {
        GithubAppProperties githubProperties = githubProperties();
        expiryMonitor = new GithubTokenExpiryMonitor();
        service = new GithubOAuthService(
                oAuthClient, apiClient, githubUserService, userTokenService, expiryMonitor,
                stateStore, codeGuard, loginCodeStore,
                new RedirectUriValidator(githubProperties), jwtProperties());
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

            String redirect = service.handleCallback(CODE, STATE, null, null);

            assertThat(redirect).startsWith("https://galpi.dev/auth/callback?code=one-time-login-code");
        }

        @Test
        @DisplayName("Access JWT를 리다이렉트 URL에 담지 않는다")
        void neverPutsAccessTokenInUrl() {
            givenHappyPath();

            String redirect = service.handleCallback(CODE, STATE, null, null);

            assertThat(redirect)
                    .doesNotContain(USER_TOKEN)
                    .doesNotContain("access_token")
                    .doesNotContain("eyJ");
        }

        @Test
        @DisplayName("발급받은 토큰과 GitHub expires_in을 토큰 서비스에 그대로 넘긴다")
        void handsTokenToTokenService() {
            givenHappyPath();

            service.handleCallback(CODE, STATE, null, null);

            ArgumentCaptor<Duration> expiresIn = ArgumentCaptor.forClass(Duration.class);
            verify(userTokenService).save(any(User.class), eq(USER_TOKEN), expiresIn.capture());
            assertThat(expiresIn.getValue()).isEqualTo(Duration.ofSeconds(28800));
        }

        @Test
        @DisplayName("refresh token은 어디에도 넘기지 않는다")
        void discardsRefreshToken() {
            givenHappyPath();

            service.handleCallback(CODE, STATE, null, null);

            verify(userTokenService).save(any(User.class), eq(USER_TOKEN), any());
            verify(userTokenService, never()).save(any(), eq("ghr_refreshtokenvalue"), any());
        }

        @Test
        @DisplayName("복귀 경로가 있으면 returnTo로 함께 넘긴다")
        void preservesReturnPath() {
            givenHappyPath();
            given(stateStore.consume(STATE)).willReturn(Optional.of("/projects/3"));

            String redirect = service.handleCallback(CODE, STATE, null, null);

            assertThat(redirect).contains("returnTo=%2Fprojects%2F3");
        }
    }

    @Nested
    @DisplayName("state 검증")
    class StateValidation {

        @Test
        @DisplayName("state 없이 콜백을 호출하면 거부한다")
        void rejectsMissingState() {
            given(stateStore.consume(null)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.handleCallback(CODE, null, null, null))
                    .isInstanceOf(BadRequestException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_OAUTH_STATE_INVALID);
        }

        @Test
        @DisplayName("이미 사용된 state는 두 번째 호출에서 거부한다")
        void rejectsReusedState() {
            // consume은 조회와 동시에 삭제하므로 두 번째 호출은 empty를 돌려준다.
            given(stateStore.consume(STATE)).willReturn(Optional.of("")).willReturn(Optional.empty());
            given(codeGuard.markUsed(CODE)).willReturn(true);
            given(oAuthClient.exchangeCodeForToken(CODE)).willReturn(tokenResponse(28800L));
            given(apiClient.getAuthenticatedUser(USER_TOKEN))
                    .willReturn(new GithubUserResponse(1L, "octocat", "https://avatars/1", null, "Octo"));
            given(githubUserService.upsert(any())).willReturn(userWithId(7L));
            given(loginCodeStore.issue(anyLong(), any())).willReturn("code");

            service.handleCallback(CODE, STATE, null, null);

            assertThatThrownBy(() -> service.handleCallback(CODE, STATE, null, null))
                    .isInstanceOf(BadRequestException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_OAUTH_STATE_INVALID);
        }

        @Test
        @DisplayName("state가 유효하지 않으면 토큰 교환을 시도하지 않는다")
        void doesNotExchangeCodeWhenStateInvalid() {
            given(stateStore.consume(STATE)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.handleCallback(CODE, STATE, null, null))
                    .isInstanceOf(BadRequestException.class);

            verify(oAuthClient, never()).exchangeCodeForToken(any());
        }
    }

    @Nested
    @DisplayName("code 재사용 차단")
    class CodeReuse {

        @Test
        @DisplayName("이미 사용된 code면 거부한다")
        void rejectsReusedCode() {
            given(stateStore.consume(STATE)).willReturn(Optional.of(""));
            given(codeGuard.markUsed(CODE)).willReturn(false);

            assertThatThrownBy(() -> service.handleCallback(CODE, STATE, null, null))
                    .isInstanceOf(BadRequestException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_OAUTH_CODE_REUSED);

            verify(oAuthClient, never()).exchangeCodeForToken(any());
        }

        @Test
        @DisplayName("code가 비어 있으면 거부한다")
        void rejectsBlankCode() {
            assertThatThrownBy(() -> service.handleCallback("  ", STATE, null, null))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("오류 경로")
    class ErrorPath {

        @Test
        @DisplayName("GitHub이 error를 주면 프론트 오류 화면으로 넘긴다")
        void redirectsToErrorPage() {
            String redirect = service.handleCallback(
                    null, STATE, "access_denied", "The user has denied your application access.");

            assertThat(redirect)
                    .startsWith("https://galpi.dev/auth/callback?error=")
                    .contains(ErrorCode.GITHUB_OAUTH_FAILED.getCode());
        }

        @Test
        @DisplayName("error가 있으면 state를 소비하지 않는다")
        void doesNotConsumeStateOnError() {
            service.handleCallback(null, STATE, "access_denied", null);

            verify(stateStore, never()).consume(any());
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

            service.handleCallback(CODE, STATE, null, null);

            assertThat(expiryMonitor.isExpirationDisabled()).isTrue();
        }

        @Test
        @DisplayName("expires_in이 없으면 만료 시각 없음(null)으로 넘긴다")
        void passesNullExpiry() {
            givenHappyPath();
            given(oAuthClient.exchangeCodeForToken(CODE))
                    .willReturn(new GithubAccessTokenResponse(
                            USER_TOKEN, "bearer", null, null, null, null, null));

            service.handleCallback(CODE, STATE, null, null);

            verify(userTokenService).save(any(User.class), eq(USER_TOKEN), eq((Duration) null));
        }

        @Test
        @DisplayName("expires_in이 있으면 만료 비활성 플래그가 서지 않는다")
        void doesNotFlagWhenExpiryPresent() {
            givenHappyPath();

            service.handleCallback(CODE, STATE, null, null);

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

            String url = service.buildAuthorizeUrl("/projects");

            assertThat(url).contains(STATE);
            verify(stateStore).issue("/projects");
        }

        @Test
        @DisplayName("허용되지 않은 복귀 대상은 거부한다")
        void rejectsOpenRedirect() {
            assertThatThrownBy(() -> service.buildAuthorizeUrl("https://evil.example.com/steal"))
                    .isInstanceOf(BadRequestException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.GITHUB_REDIRECT_NOT_ALLOWED);
        }
    }
}

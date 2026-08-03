package com.github.galpiii.galpi.domain.auth.service;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.auth.dto.IssuedTokens;
import com.github.galpiii.galpi.domain.auth.dto.MeResponse;
import com.github.galpiii.galpi.domain.auth.jwt.JwtTokenProvider;
import com.github.galpiii.galpi.domain.auth.jwt.TokenType;
import com.github.galpiii.galpi.domain.auth.store.LoginCodeStore;
import com.github.galpiii.galpi.domain.auth.store.RefreshTokenStore;
import com.github.galpiii.galpi.domain.github.service.GithubUserTokenService;
import com.github.galpiii.galpi.domain.user.entity.GithubConnectionStatus;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
@DisplayName("AuthService — 갈피 세션")
class AuthServiceTest {

    private static final long USER_ID = 7L;

    @Mock
    private RefreshTokenStore refreshTokenStore;
    @Mock
    private LoginCodeStore loginCodeStore;
    @Mock
    private GithubUserTokenService githubUserTokenService;
    @Mock
    private UserRepository userRepository;

    private JwtTokenProvider tokenProvider;
    private AuthService service;

    private static JwtProperties jwtProperties() {
        return new JwtProperties(
                "galpi-test-secret-key-must-be-at-least-32-bytes", "galpi",
                Duration.ofMinutes(30), Duration.ofDays(14), Duration.ofDays(90), Duration.ofSeconds(60),
                new JwtProperties.Cookie("galpi_refresh", "/auth", true, "Lax", ""));
    }

    private static User userWithId(long id) {
        User user = User.ofGithub(999L, "octocat", "Octo", "dev@galpi.dev", "https://avatars/1");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @BeforeEach
    void setUp() {
        JwtProperties properties = jwtProperties();
        tokenProvider = new JwtTokenProvider(properties);
        service = new AuthService(tokenProvider, properties, refreshTokenStore, loginCodeStore,
                githubUserTokenService, userRepository);
    }

    @Nested
    @DisplayName("로그인 코드 교환")
    class ExchangeLoginCode {

        @Test
        @DisplayName("코드를 소비하고 토큰 쌍을 발급한다")
        void issuesTokens() {
            given(loginCodeStore.consume("login-code")).willReturn(Optional.of(USER_ID));

            IssuedTokens tokens = service.exchangeLoginCode("login-code");

            assertThat(tokenProvider.parse(tokens.accessToken(), TokenType.ACCESS).userId())
                    .isEqualTo(USER_ID);
            assertThat(tokenProvider.parse(tokens.refreshToken(), TokenType.REFRESH).userId())
                    .isEqualTo(USER_ID);
            assertThat(tokens.accessTokenExpiresInSeconds()).isEqualTo(1800);
            verify(refreshTokenStore).save(eq(tokens.refreshToken()), eq(USER_ID), any());
        }

        @Test
        @DisplayName("이미 사용된 코드는 거부한다")
        void rejectsConsumedCode() {
            given(loginCodeStore.consume("used")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.exchangeLoginCode("used"))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_LOGIN_CODE);
        }

        @Test
        @DisplayName("응답 DTO의 toString에 토큰이 남지 않는다")
        void toStringHidesTokens() {
            given(loginCodeStore.consume("login-code")).willReturn(Optional.of(USER_ID));

            IssuedTokens tokens = service.exchangeLoginCode("login-code");

            assertThat(tokens.toString())
                    .doesNotContain(tokens.accessToken())
                    .doesNotContain(tokens.refreshToken());
        }
    }

    @Nested
    @DisplayName("Refresh 회전")
    class Refresh {

        @Test
        @DisplayName("기존 토큰을 소비하고 새 토큰 쌍을 발급한다")
        void rotatesRefreshToken() {
            String oldRefresh = tokenProvider.createRefreshToken(USER_ID);
            given(refreshTokenStore.consume(oldRefresh)).willReturn(Optional.of(USER_ID));
            given(userRepository.existsById(USER_ID)).willReturn(true);

            IssuedTokens tokens = service.refresh(oldRefresh);

            assertThat(tokens.refreshToken()).isNotEqualTo(oldRefresh);
            verify(refreshTokenStore).consume(oldRefresh);
            verify(refreshTokenStore).save(eq(tokens.refreshToken()), eq(USER_ID), any());
        }

        @Test
        @DisplayName("회전해도 세션 시작 시각은 물려받는다 — 회전으로 절대 수명을 늘릴 수 없다")
        void carriesSessionStartAcrossRotation() {
            Instant sessionStartedAt = Instant.now().minus(Duration.ofDays(30));
            String oldRefresh = tokenProvider.createRefreshToken(USER_ID, sessionStartedAt);
            given(refreshTokenStore.consume(oldRefresh)).willReturn(Optional.of(USER_ID));
            given(userRepository.existsById(USER_ID)).willReturn(true);

            IssuedTokens tokens = service.refresh(oldRefresh);

            assertThat(tokenProvider.parse(tokens.refreshToken(), TokenType.REFRESH).sessionStartedAt())
                    .isEqualTo(sessionStartedAt.truncatedTo(ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("절대 수명이 지난 세션은 회전을 거부하고 남은 세션도 정리한다")
        void rejectsSessionPastAbsoluteTtl() {
            String refresh = tokenProvider.createRefreshToken(
                    USER_ID, Instant.now().minus(Duration.ofDays(91)));
            given(refreshTokenStore.consume(refresh)).willReturn(Optional.of(USER_ID));

            assertThatThrownBy(() -> service.refresh(refresh))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SESSION_EXPIRED);

            verify(refreshTokenStore).revokeAll(USER_ID);
            verify(refreshTokenStore, never()).save(any(), any(), any());
        }

        @Test
        @DisplayName("세션 시작 시각이 없는 토큰은 거부한다 — 없는 값을 지금으로 메우면 상한이 무력화된다")
        void rejectsTokenWithoutSessionStart() {
            String accessShapedRefresh = tokenProvider.createRefreshToken(USER_ID, null);
            given(refreshTokenStore.consume(accessShapedRefresh)).willReturn(Optional.of(USER_ID));

            assertThatThrownBy(() -> service.refresh(accessShapedRefresh))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("탈퇴한 사용자는 저장소에 토큰이 남아 있어도 재발급받지 못한다")
        void rejectsDeletedUser() {
            String refresh = tokenProvider.createRefreshToken(USER_ID);
            given(refreshTokenStore.consume(refresh)).willReturn(Optional.of(USER_ID));
            given(userRepository.existsById(USER_ID)).willReturn(false);

            assertThatThrownBy(() -> service.refresh(refresh))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);

            verify(refreshTokenStore).revokeAll(USER_ID);
            verify(refreshTokenStore, never()).save(any(), any(), any());
        }

        @Test
        @DisplayName("살아 있는 토큰이 저장소에 없으면 재사용으로 보고 모든 세션을 폐기한다")
        void revokesEverySessionOnReuse() {
            String refresh = tokenProvider.createRefreshToken(USER_ID);
            given(refreshTokenStore.consume(refresh)).willReturn(Optional.empty());
            given(refreshTokenStore.wasRevoked(refresh)).willReturn(false);

            assertThatThrownBy(() -> service.refresh(refresh))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSED);

            verify(refreshTokenStore).revokeAll(USER_ID);
        }

        @Test
        @DisplayName("재사용이면 새 토큰을 발급하지 않는다")
        void issuesNothingOnReuse() {
            String refresh = tokenProvider.createRefreshToken(USER_ID);
            given(refreshTokenStore.consume(refresh)).willReturn(Optional.empty());
            given(refreshTokenStore.wasRevoked(refresh)).willReturn(false);

            assertThatThrownBy(() -> service.refresh(refresh));

            verify(refreshTokenStore, never()).save(any(), any(), any());
        }

        @Test
        @DisplayName("동시 갱신에서 진 요청은 재사용으로 오판하지 않는다 — 다른 기기 세션을 지키다")
        void doesNotTreatConcurrentRefreshAsReuse() {
            String refresh = tokenProvider.createRefreshToken(USER_ID);
            given(refreshTokenStore.consume(refresh)).willReturn(Optional.empty());
            given(refreshTokenStore.wasRevoked(refresh)).willReturn(false);
            given(refreshTokenStore.wasRecentlyConsumed(refresh)).willReturn(true);

            assertThatThrownBy(() -> service.refresh(refresh))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND);

            verify(refreshTokenStore, never()).revokeAll(any());
        }

        @Test
        @DisplayName("유예 창을 지나 다시 오면 그때는 재사용으로 본다")
        void stillDetectsReuseAfterGraceWindow() {
            String refresh = tokenProvider.createRefreshToken(USER_ID);
            given(refreshTokenStore.consume(refresh)).willReturn(Optional.empty());
            given(refreshTokenStore.wasRevoked(refresh)).willReturn(false);
            given(refreshTokenStore.wasRecentlyConsumed(refresh)).willReturn(false);

            assertThatThrownBy(() -> service.refresh(refresh))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_REUSED);

            verify(refreshTokenStore).revokeAll(USER_ID);
        }

        @Test
        @DisplayName("로그아웃으로 폐기된 토큰은 재사용으로 오판하지 않는다")
        void doesNotTreatLoggedOutTokenAsReuse() {
            String refresh = tokenProvider.createRefreshToken(USER_ID);
            given(refreshTokenStore.consume(refresh)).willReturn(Optional.empty());
            given(refreshTokenStore.wasRevoked(refresh)).willReturn(true);

            assertThatThrownBy(() -> service.refresh(refresh))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND);

            verify(refreshTokenStore, never()).revokeAll(any());
        }

        @Test
        @DisplayName("쿠키가 없으면 거부한다")
        void rejectsMissingCookie() {
            assertThatThrownBy(() -> service.refresh(null))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        @Test
        @DisplayName("Access 토큰을 refresh로 쓰면 거부한다")
        void rejectsAccessTokenAsRefresh() {
            String accessToken = tokenProvider.createAccessToken(USER_ID);

            assertThatThrownBy(() -> service.refresh(accessToken))
                    .isInstanceOf(UnauthorizedException.class)
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TOKEN);

            verify(refreshTokenStore, never()).consume(any());
        }

        @Test
        @DisplayName("저장된 주체와 토큰의 주체가 다르면 거부한다")
        void rejectsSubjectMismatch() {
            String refresh = tokenProvider.createRefreshToken(USER_ID);
            given(refreshTokenStore.consume(refresh)).willReturn(Optional.of(999L));

            assertThatThrownBy(() -> service.refresh(refresh))
                    .extracting(e -> ((GlobalException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TOKEN);
        }
    }

    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("제출된 refresh 토큰만 폐기한다")
        void revokesPresentedRefreshToken() {
            service.logout("refresh-token");

            verify(refreshTokenStore).revoke("refresh-token");
            verify(refreshTokenStore, never()).revokeAll(any());
        }

        @Test
        @DisplayName("GitHub 연결은 건드리지 않는다 — 다른 기기에서 계속 쓸 수 있어야 한다")
        void keepsGithubConnection() {
            service.logout("refresh-token");

            verify(githubUserTokenService, never()).delete(any());
        }

        @Test
        @DisplayName("쿠키가 없으면 조용히 끝낸다")
        void toleratesMissingCookie() {
            service.logout(null);

            verify(refreshTokenStore, never()).revoke(any());
            verify(githubUserTokenService, never()).delete(any());
        }

        @Test
        @DisplayName("이미 만료된 쿠키여도 예외 없이 끝낸다")
        void toleratesStaleCookie() {
            service.logout("stale-token");

            verify(refreshTokenStore).revoke("stale-token");
            verify(githubUserTokenService, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("내 정보 — GitHub 토큰 만료와 무관하게 동작")
    class Me {

        @Test
        @DisplayName("GitHub 토큰이 만료돼도 조회에 성공하고 재연결 필요를 알린다")
        void succeedsWhenGithubTokenExpired() {
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(userWithId(USER_ID)));
            given(githubUserTokenService.isValid(USER_ID)).willReturn(false);

            MeResponse me = service.getMe(USER_ID);

            assertThat(me.userId()).isEqualTo(USER_ID);
            assertThat(me.github().githubTokenValid()).isFalse();
            assertThat(me.github().githubId()).isEqualTo(999L);
            assertThat(me.github().connectionStatus()).isEqualTo(GithubConnectionStatus.CONNECTED);
        }

        @Test
        @DisplayName("토큰이 유효하면 재연결이 필요 없다고 알린다")
        void reportsValidToken() {
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(userWithId(USER_ID)));
            given(githubUserTokenService.isValid(USER_ID)).willReturn(true);

            assertThat(service.getMe(USER_ID).github().githubTokenValid()).isTrue();
        }

        @Test
        @DisplayName("없는 회원이면 401")
        void rejectsUnknownUser() {
            given(userRepository.findById(anyLong())).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getMe(123L))
                    .isInstanceOf(UnauthorizedException.class);
        }
    }
}

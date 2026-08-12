package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.auth.store.RefreshTokenStore;
import com.github.galpiii.galpi.domain.auth.support.RedirectUriValidator;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.dto.InstallUrlResponse;
import com.github.galpiii.galpi.domain.github.store.GithubInstallStateStore;
import com.github.galpiii.galpi.domain.github.store.GithubInstallStateStore.InstallIntent;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GithubSetupService — 설치 시작과 setup 콜백")
class GithubSetupServiceTest {

    private static final long USER_ID = 7L;
    private static final long INSTALLATION_ID = 4242L;
    private static final String STATE = "install-state-value";
    private static final String RETURN_TO = "/projects/3/repositories";
    private static final String REFRESH_TOKEN = "refresh-token-value";

    @Mock
    private GithubInstallStateStore installStateStore;
    @Mock
    private GithubInstallationService installationService;
    @Mock
    private RefreshTokenStore refreshTokenStore;

    private GithubSetupService service;

    @BeforeEach
    void setUp() {
        GithubAppProperties properties = properties();
        service = new GithubSetupService(
                installStateStore, installationService,
                new RedirectUriValidator(properties), refreshTokenStore, properties);
    }

    private static GithubAppProperties properties() {
        return new GithubAppProperties(
                "app-id", "galpi-app", "client-id", "client-secret", "private-key",
                "https://api.galpi.dev", "2022-11-28", "https://api.github.com",
                "https://github.com", "Galpi", List.of("https://galpi.dev"),
                "https://galpi.dev/auth/callback",
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2, 10);
    }

    private static InstallIntent intent(String returnTo) {
        return new InstallIntent(USER_ID, STATE, returnTo, OffsetDateTime.now());
    }

    @Nested
    @DisplayName("설치 URL 발급")
    class InstallUrl {

        @Test
        @DisplayName("select_target이 아니라 installations/new로 보낸다")
        void usesInstallationsNew() {
            given(installStateStore.issue(USER_ID, RETURN_TO)).willReturn(STATE);

            InstallUrlResponse response = service.buildInstallUrl(USER_ID, RETURN_TO);

            assertThat(response.installUrl())
                    .isEqualTo("https://github.com/apps/galpi-app/installations/new?state=" + STATE)
                    .doesNotContain("select_target");
        }

        @Test
        @DisplayName("허용 목록 밖 returnTo는 URL을 만들기 전에 거부한다")
        void rejectsExternalReturnTo() {
            assertThatThrownBy(() -> service.buildInstallUrl(USER_ID, "https://evil.example/steal"))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GITHUB_REDIRECT_NOT_ALLOWED);

            verify(installStateStore, never()).issue(anyLong(), anyString());
        }
    }

    @Nested
    @DisplayName("setup 콜백 — 사용자 식별")
    class UserResolution {

        @Test
        @DisplayName("state가 살아 돌아오면 그것으로 확인하고 사용자 키도 함께 지운다")
        void resolvesByState() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.of(intent(RETURN_TO)));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);

            String redirect = service.handleSetupCallback(
                    INSTALLATION_ID, "install", STATE, REFRESH_TOKEN);

            assertThat(redirect).contains("installation=verified");
            verify(installStateStore).clearIntent(USER_ID);
            verify(refreshTokenStore, never()).peek(anyString());
        }

        @Test
        @DisplayName("state가 유실되면 세션 쿠키로 사용자를 찾고 설치 시작 기록을 확인한다")
        void fallsBackToSessionAndIntent() {
            given(installStateStore.consumeState(null)).willReturn(Optional.empty());
            given(refreshTokenStore.peek(REFRESH_TOKEN)).willReturn(Optional.of(USER_ID));
            given(installStateStore.consumeIntent(USER_ID)).willReturn(Optional.of(intent(RETURN_TO)));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);

            String redirect = service.handleSetupCallback(
                    INSTALLATION_ID, "install", null, REFRESH_TOKEN);

            assertThat(redirect).contains("installation=verified");
        }

        @Test
        @DisplayName("로그인은 되어 있지만 설치를 시작한 기록이 없으면 거부한다")
        void rejectsLoggedInUserWithoutIntent() {
            given(installStateStore.consumeState(null)).willReturn(Optional.empty());
            given(refreshTokenStore.peek(REFRESH_TOKEN)).willReturn(Optional.of(USER_ID));
            given(installStateStore.consumeIntent(USER_ID)).willReturn(Optional.empty());

            String redirect = service.handleSetupCallback(
                    INSTALLATION_ID, "install", null, REFRESH_TOKEN);

            assertThat(redirect).contains("error=" + ErrorCode.GITHUB_INSTALL_NOT_STARTED.getCode());
            verify(installationService, never()).ownsInstallation(anyLong(), anyLong());
        }

        @Test
        @DisplayName("세션도 state도 없으면 거부한다")
        void rejectsAnonymousCallback() {
            given(installStateStore.consumeState(any())).willReturn(Optional.empty());
            given(refreshTokenStore.peek(any())).willReturn(Optional.empty());

            String redirect = service.handleSetupCallback(INSTALLATION_ID, "install", null, null);

            assertThat(redirect).contains("error=" + ErrorCode.GITHUB_INSTALL_NOT_STARTED.getCode());
        }
    }

    @Nested
    @DisplayName("setup 콜백 — installation_id 대조")
    class InstallationVerification {

        @Test
        @DisplayName("남의 installation_id는 확인되지 않음으로 떨어뜨린다")
        void rejectsForgedInstallationId() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.of(intent(RETURN_TO)));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(false);

            String redirect = service.handleSetupCallback(
                    INSTALLATION_ID, "install", STATE, REFRESH_TOKEN);

            assertThat(redirect).contains("installation=unverified");
        }

        @Test
        @DisplayName("installation_id가 없어도 오류가 아니다 — 조직 승인 대기로 본다")
        void treatsMissingInstallationIdAsPending() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.of(intent(RETURN_TO)));

            String redirect = service.handleSetupCallback(null, "request", STATE, REFRESH_TOKEN);

            assertThat(redirect).contains("installation=unverified");
            verify(installationService, never()).ownsInstallation(anyLong(), anyLong());
        }

        @Test
        @DisplayName("알 수 없는 setup_action이 와도 흐름을 바꾸지 않는다")
        void ignoresUnknownSetupAction() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.of(intent(RETURN_TO)));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);

            assertThat(service.handleSetupCallback(INSTALLATION_ID, "brand-new-value", STATE, null))
                    .contains("installation=verified");
            assertThat(service.handleSetupCallback(INSTALLATION_ID, null, STATE, null))
                    .contains("installation=verified");
        }
    }

    @Nested
    @DisplayName("setup 콜백 — 복귀 경로")
    class ReturnTo {

        @Test
        @DisplayName("복귀 경로를 리다이렉트에 실어 준다")
        void carriesReturnTo() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.of(intent(RETURN_TO)));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);

            assertThat(service.handleSetupCallback(INSTALLATION_ID, "install", STATE, null))
                    .startsWith("https://galpi.dev/auth/callback?")
                    .contains("returnTo=");
        }

        @Test
        @DisplayName("저장된 복귀 경로가 외부 URL이면 버리고 기본 화면으로 보낸다")
        void dropsExternalStoredReturnTo() {
            given(installStateStore.consumeState(STATE))
                    .willReturn(Optional.of(intent("https://evil.example/steal")));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);

            String redirect = service.handleSetupCallback(INSTALLATION_ID, "install", STATE, null);

            assertThat(redirect).startsWith("https://galpi.dev/auth/callback?")
                    .doesNotContain("evil.example");
        }
    }
}

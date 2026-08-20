package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.auth.support.RedirectUriValidator;
import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.dto.InstallUrlResponse;
import com.github.galpiii.galpi.domain.github.dto.RepositorySnapshot;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
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
import java.util.Map;
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

    @Mock
    private GithubInstallStateStore installStateStore;
    @Mock
    private GithubInstallationService installationService;

    private GithubSetupService service;

    @BeforeEach
    void setUp() {
        GithubAppProperties properties = properties();
        service = new GithubSetupService(
                installStateStore, installationService,
                new RedirectUriValidator(properties), properties);
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
        return new InstallIntent(USER_ID, STATE, returnTo, List.of(), OffsetDateTime.now());
    }

    private static InstallIntent intentWithSelection(String returnTo, List<Long> selected) {
        return new InstallIntent(USER_ID, STATE, returnTo, selected, OffsetDateTime.now());
    }

    @Nested
    @DisplayName("설치 URL 발급")
    class InstallUrl {

        @Test
        @DisplayName("select_target이 아니라 installations/new로 보낸다")
        void usesInstallationsNew() {
            given(installStateStore.issue(USER_ID, RETURN_TO, List.of())).willReturn(STATE);

            InstallUrlResponse response = service.buildInstallUrl(USER_ID, RETURN_TO, List.of());

            assertThat(response.installUrl())
                    .isEqualTo("https://github.com/apps/galpi-app/installations/new?state=" + STATE)
                    .doesNotContain("select_target");
        }

        @Test
        @DisplayName("허용 목록 밖 returnTo는 URL을 만들기 전에 거부한다")
        void rejectsExternalReturnTo() {
            assertThatThrownBy(() ->
                    service.buildInstallUrl(USER_ID, "https://evil.example/steal", List.of()))
                    .isInstanceOf(BadRequestException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GITHUB_REDIRECT_NOT_ALLOWED);

            verify(installStateStore, never()).issue(anyLong(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("setup 콜백 — 사용자 식별")
    class UserResolution {

        @Test
        @DisplayName("state가 살아 돌아오면 그것으로 확인한다")
        void resolvesByState() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.of(intent(RETURN_TO)));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);

            String redirect = service.handleSetupCallback(INSTALLATION_ID, "install", STATE);

            assertThat(redirect).contains("installation=verified");
        }

        @Test
        @DisplayName("state가 없으면 거부한다 — 세션으로 대신 받아내지 않는다")
        void rejectsCallbackWithoutState() {
            given(installStateStore.consumeState(null)).willReturn(Optional.empty());

            String redirect = service.handleSetupCallback(INSTALLATION_ID, "install", null);

            assertThat(redirect).contains("error=" + ErrorCode.GITHUB_INSTALL_NOT_STARTED.getCode());
            verify(installationService, never()).ownsInstallation(anyLong(), anyLong());
        }

        @Test
        @DisplayName("만료·재사용된 state도 거부한다")
        void rejectsUnknownState() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.empty());

            String redirect = service.handleSetupCallback(INSTALLATION_ID, "install", STATE);

            assertThat(redirect).contains("error=" + ErrorCode.GITHUB_INSTALL_NOT_STARTED.getCode());
            verify(installationService, never()).ownsInstallation(anyLong(), anyLong());
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

            String redirect = service.handleSetupCallback(INSTALLATION_ID, "install", STATE);

            assertThat(redirect).contains("installation=unverified");
        }

        @Test
        @DisplayName("대조 자체가 실패해도 리다이렉트를 유지한다 — 콜백에서 JSON 오류가 나가면 안 된다")
        void keepsRedirectWhenVerificationFails() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.of(intent(RETURN_TO)));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID))
                    .willThrow(new GithubApiException(ErrorCode.GITHUB_REPOSITORY_LIST_INCOMPLETE));

            String redirect = service.handleSetupCallback(INSTALLATION_ID, "install", STATE);

            assertThat(redirect).contains("installation=unverified");
        }

        @Test
        @DisplayName("재연결이 필요해도 리다이렉트를 유지한다")
        void keepsRedirectWhenTokenExpired() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.of(intent(RETURN_TO)));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID))
                    .willThrow(new GithubReauthRequiredException());

            String redirect = service.handleSetupCallback(INSTALLATION_ID, "install", STATE);

            assertThat(redirect).contains("installation=unverified");
        }

        @Test
        @DisplayName("installation_id가 없어도 오류가 아니다 — 조직 승인 대기로 본다")
        void treatsMissingInstallationIdAsPending() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.of(intent(RETURN_TO)));

            String redirect = service.handleSetupCallback(null, "request", STATE);

            assertThat(redirect).contains("installation=unverified");
            verify(installationService, never()).ownsInstallation(anyLong(), anyLong());
        }

        @Test
        @DisplayName("알 수 없는 setup_action이 와도 흐름을 바꾸지 않는다")
        void ignoresUnknownSetupAction() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.of(intent(RETURN_TO)));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);

            assertThat(service.handleSetupCallback(INSTALLATION_ID, "brand-new-value", STATE))
                    .contains("installation=verified");
            assertThat(service.handleSetupCallback(INSTALLATION_ID, null, STATE))
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

            assertThat(service.handleSetupCallback(INSTALLATION_ID, "install", STATE))
                    .startsWith("https://galpi.dev/auth/callback?")
                    .contains("returnTo=");
        }

        @Test
        @DisplayName("저장된 복귀 경로가 외부 URL이면 버리고 기본 화면으로 보낸다")
        void dropsExternalStoredReturnTo() {
            given(installStateStore.consumeState(STATE))
                    .willReturn(Optional.of(intent("https://evil.example/steal")));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);

            String redirect = service.handleSetupCallback(INSTALLATION_ID, "install", STATE);

            assertThat(redirect).startsWith("https://galpi.dev/auth/callback?")
                    .doesNotContain("evil.example");
        }
    }

    @Nested
    @DisplayName("setup 콜백 — 선택 상태 복원")
    class SelectionRestore {

        private final RepositorySnapshot snapshot = new RepositorySnapshot(
                11L, INSTALLATION_ID, "wb", "notes", "wb/notes", true, "main",
                "https://github.com/wb/notes");

        @Test
        @DisplayName("설치 화면에 나가기 전 고른 저장소를 리다이렉트에 실어 돌려준다")
        void restoresSelection() {
            given(installStateStore.consumeState(STATE)).willReturn(
                    Optional.of(intentWithSelection(RETURN_TO, List.of(11L))));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);
            given(installationService.accessibleSnapshots(USER_ID, List.of(11L)))
                    .willReturn(Map.of(11L, snapshot));

            String redirect = service.handleSetupCallback(INSTALLATION_ID, "install", STATE);

            assertThat(redirect).contains("selectedRepositoryIds=11");
            assertThat(redirect).doesNotContain("unavailableRepositoryIds");
        }

        @Test
        @DisplayName("그 사이 접근할 수 없게 된 저장소는 선택에서 빼고 따로 알린다")
        void dropsRepositoriesThatBecameInaccessible() {
            given(installStateStore.consumeState(STATE)).willReturn(
                    Optional.of(intentWithSelection(RETURN_TO, List.of(11L, 22L))));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);
            given(installationService.accessibleSnapshots(USER_ID, List.of(11L, 22L)))
                    .willReturn(Map.of(11L, snapshot));

            String redirect = service.handleSetupCallback(INSTALLATION_ID, "install", STATE);

            assertThat(redirect).contains("selectedRepositoryIds=11");
            assertThat(redirect).contains("unavailableRepositoryIds=22");
        }

        @Test
        @DisplayName("권한 대조가 실패하면 선택을 버리지 않고 그대로 돌려준다")
        void keepsSelectionWhenVerificationFails() {
            given(installStateStore.consumeState(STATE)).willReturn(
                    Optional.of(intentWithSelection(RETURN_TO, List.of(11L))));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);
            given(installationService.accessibleSnapshots(USER_ID, List.of(11L)))
                    .willThrow(new GithubApiException());

            assertThat(service.handleSetupCallback(INSTALLATION_ID, "install", STATE))
                    .contains("selectedRepositoryIds=11");
        }

        @Test
        @DisplayName("설치가 확인되지 않은 경로에서도 선택은 살아 돌아온다")
        void restoresSelectionOnUnverifiedResult() {
            given(installStateStore.consumeState(STATE)).willReturn(
                    Optional.of(intentWithSelection(RETURN_TO, List.of(11L))));
            given(installationService.accessibleSnapshots(USER_ID, List.of(11L)))
                    .willReturn(Map.of(11L, snapshot));

            assertThat(service.handleSetupCallback(null, "request", STATE))
                    .contains("installation=unverified")
                    .contains("selectedRepositoryIds=11");
        }

        @Test
        @DisplayName("고른 것이 없으면 권한을 대조하지도 않는다")
        void skipsVerificationWithoutSelection() {
            given(installStateStore.consumeState(STATE)).willReturn(Optional.of(intent(RETURN_TO)));
            given(installationService.ownsInstallation(USER_ID, INSTALLATION_ID)).willReturn(true);

            assertThat(service.handleSetupCallback(INSTALLATION_ID, "install", STATE))
                    .doesNotContain("selectedRepositoryIds");
            verify(installationService, never()).accessibleSnapshots(anyLong(), any());
        }
    }
}

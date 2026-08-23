package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.dto.GithubDisconnectResponse;
import com.github.galpiii.galpi.domain.github.entity.GithubRevocationType;
import com.github.galpiii.galpi.domain.github.event.GithubDisconnectedEvent;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("GithubConnectionService — 연결 해제")
class GithubConnectionServiceTest {

    private static final long USER_ID = 7L;
    private static final String TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz012345";

    @Mock
    private GithubUserTokenService userTokenService;
    @Mock
    private GithubUserWriter userWriter;
    @Mock
    private GithubTokenRevoker tokenRevoker;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GithubConnectionService service;

    @BeforeEach
    void setUp() {
        service = new GithubConnectionService(userTokenService, userWriter, tokenRevoker,
                userRepository, properties(), eventPublisher);
    }

    @Test
    @DisplayName("로컬 원본을 지우기 전에 폐기를 넘긴다 — 순서가 뒤집히면 회수 수단이 사라진다")
    void handsOffRevocationBeforeDeletingLocalCopy() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));

        service.disconnect(USER_ID);

        InOrder order = inOrder(tokenRevoker, userTokenService, userWriter);
        order.verify(tokenRevoker)
                .revokeOrEnqueue(USER_ID, TOKEN, GithubRevocationType.GRANT);
        order.verify(userTokenService).delete(USER_ID);
        order.verify(userWriter).disconnectGithub(USER_ID);
    }

    @Test
    @DisplayName("토큰 하나가 아니라 authorization 전체를 폐기한다")
    void revokesWholeAuthorization() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));
        given(tokenRevoker.revokeOrEnqueue(USER_ID, TOKEN, GithubRevocationType.GRANT))
                .willReturn(true);

        GithubDisconnectResponse response = service.disconnect(USER_ID);

        assertThat(response.authorizationRevoked()).isTrue();
        verify(tokenRevoker).revokeOrEnqueue(USER_ID, TOKEN, GithubRevocationType.GRANT);
    }

    @Test
    @DisplayName("진행 중인 분석을 멈추도록 연결 해제를 알린다")
    void announcesDisconnect() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));

        service.disconnect(USER_ID);

        ArgumentCaptor<GithubDisconnectedEvent> published =
                ArgumentCaptor.forClass(GithubDisconnectedEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue().userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("폐기 인계 자체가 실패하면 연결 해제를 중단한다 — 회수 못 하는 토큰을 두고 성공이라 할 수 없다")
    void abortsWhenRevocationCannotBeHandedOff() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));
        willThrow(new DataAccessResourceFailureException("db down"))
                .given(tokenRevoker).revokeOrEnqueue(anyLong(), anyString(), any());

        assertThatThrownBy(() -> service.disconnect(USER_ID))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(userTokenService, never()).delete(any());
        verify(userWriter, never()).disconnectGithub(any());
    }

    @Test
    @DisplayName("토큰이 없거나 만료됐으면 재인증을 요구하지 않고 직접 해제할 링크를 준다")
    void guidesManualRevokeWhenTokenIsGone() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.empty());

        GithubDisconnectResponse response = service.disconnect(USER_ID);

        verify(tokenRevoker, never()).revokeOrEnqueue(anyLong(), anyString(), any());
        verify(userWriter).disconnectGithub(USER_ID);
        assertThat(response.authorizationRevoked()).isFalse();
        assertThat(response.authorizationsUrl())
                .isEqualTo("https://github.com/settings/apps/authorizations");
        // 설치는 자동으로 지우지 않는다. 조직 설치는 다른 사용자와 공유될 수 있다.
        assertThat(response.installationsUrl())
                .isEqualTo("https://github.com/settings/installations");
    }

    @Test
    @DisplayName("폐기가 큐로 밀리면 폐기됐다고 답하지 않는다")
    void doesNotClaimRevokedWhenQueued() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));
        given(tokenRevoker.revokeOrEnqueue(USER_ID, TOKEN, GithubRevocationType.GRANT))
                .willReturn(false);

        assertThat(service.disconnect(USER_ID).authorizationRevoked()).isFalse();
    }

    @Test
    @DisplayName("없는 회원이면 401이고 토큰도 건드리지 않는다")
    void rejectsUnknownUser() {
        given(userRepository.existsById(USER_ID)).willReturn(false);

        assertThatThrownBy(() -> service.disconnect(USER_ID))
                .isInstanceOf(UnauthorizedException.class)
                .extracting(e -> ((GlobalException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(tokenRevoker, never()).revokeOrEnqueue(anyLong(), anyString(), any());
        verify(userTokenService, never()).delete(any());
        verify(userWriter, never()).disconnectGithub(any());
        verify(eventPublisher, never()).publishEvent(any(GithubDisconnectedEvent.class));
    }

    private static GithubAppProperties properties() {
        return new GithubAppProperties(
                "12345", "galpi-app", "Iv1.client", "secret", "pem", "https://api.galpi.dev",
                "2022-11-28", "https://api.github.com", "https://github.com", "Galpi",
                List.of("https://galpi.dev"), "https://galpi.dev/auth/callback",
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2, 10);
    }
}

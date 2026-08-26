package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.dto.GithubDisconnectResponse;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("GithubConnectionService — 연결 해제")
class GithubConnectionServiceTest {

    private static final long USER_ID = 7L;
    private static final long REVOCATION_ID = 31L;
    private static final String TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz012345";

    @Mock
    private GithubUserTokenService userTokenService;
    @Mock
    private GithubTokenRevoker tokenRevoker;
    @Mock
    private GithubDisconnectWriter disconnectWriter;
    @Mock
    private UserRepository userRepository;

    private GithubConnectionService service;

    @BeforeEach
    void setUp() {
        service = new GithubConnectionService(userTokenService, tokenRevoker, disconnectWriter,
                userRepository, properties());
    }

    @Test
    @DisplayName("로컬 정리를 먼저 커밋하고 그다음에 외부 폐기를 시도한다 — 되돌릴 수 없는 호출이 앞서면 안 된다")
    void commitsLocallyBeforeCallingGithub() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));
        given(disconnectWriter.disconnect(USER_ID, TOKEN)).willReturn(REVOCATION_ID);

        service.disconnect(USER_ID);

        InOrder order = inOrder(disconnectWriter, tokenRevoker);
        order.verify(disconnectWriter).disconnect(USER_ID, TOKEN);
        order.verify(tokenRevoker).revokeGrantAfterDisconnect(USER_ID, TOKEN, REVOCATION_ID);
    }

    @Test
    @DisplayName("폐기 의도는 로컬 정리와 같은 트랜잭션에 맡긴다 — 원본을 지우고 나면 회수할 암호문이 없다")
    void handsTheRevocationIntentToTheSameTransaction() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));

        service.disconnect(USER_ID);

        // 평문을 넘기는 것이 요점이다. 지운 뒤에는 복호화할 원본이 남지 않는다.
        verify(disconnectWriter).disconnect(USER_ID, TOKEN);
        // 토큰 삭제·상태 변경·분석 취소와 마찬가지로 여기서 쪼개지 않는다.
        verify(userTokenService, never()).delete(any());
    }

    @Test
    @DisplayName("토큰 하나가 아니라 authorization 전체를 폐기한다 — 단 이 자리에서 한 번만 시도한다")
    void revokesWholeAuthorization() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));
        given(disconnectWriter.disconnect(USER_ID, TOKEN)).willReturn(REVOCATION_ID);
        given(tokenRevoker.revokeGrantAfterDisconnect(USER_ID, TOKEN, REVOCATION_ID))
                .willReturn(true);

        GithubDisconnectResponse response = service.disconnect(USER_ID);

        assertThat(response.authorizationRevoked()).isTrue();
        verify(tokenRevoker).revokeGrantAfterDisconnect(USER_ID, TOKEN, REVOCATION_ID);
    }

    @Test
    @DisplayName("로컬 정리가 실패하면 외부 폐기를 부르지 않는다 — 되돌릴 수 없는 폐기를 남기지 않는다")
    void doesNotCallGithubWhenLocalCleanupFails() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));
        willThrow(new DataAccessResourceFailureException("db down"))
                .given(disconnectWriter).disconnect(anyLong(), anyString());

        assertThatThrownBy(() -> service.disconnect(USER_ID))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(tokenRevoker, never()).revokeGrantAfterDisconnect(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("토큰이 없거나 만료됐으면 재인증을 요구하지 않고 직접 해제할 링크를 준다")
    void guidesManualRevokeWhenTokenIsGone() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.empty());

        GithubDisconnectResponse response = service.disconnect(USER_ID);

        // 폐기할 수단이 없으니 큐에 남길 것도, 부를 것도 없다.
        verify(disconnectWriter).disconnect(eq(USER_ID), isNull());
        verify(tokenRevoker, never()).revokeGrantAfterDisconnect(anyLong(), anyString(), any());
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
        given(disconnectWriter.disconnect(USER_ID, TOKEN)).willReturn(REVOCATION_ID);
        given(tokenRevoker.revokeGrantAfterDisconnect(USER_ID, TOKEN, REVOCATION_ID))
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

        verify(tokenRevoker, never()).revokeGrantAfterDisconnect(anyLong(), anyString(), any());
        verify(disconnectWriter, never()).disconnect(anyLong(), any());
    }

    private static GithubAppProperties properties() {
        return new GithubAppProperties(
                "12345", "galpi-app", "Iv1.client", "secret", "pem", "https://api.galpi.dev",
                "2022-11-28", "https://api.github.com", "https://github.com", "Galpi",
                List.of("https://galpi.dev"), "https://galpi.dev/auth/callback",
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2, 10);
    }
}

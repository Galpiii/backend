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
    @DisplayName("로컬 원본을 지우기 전에 폐기를 넘긴다 — 순서가 뒤집히면 회수 수단이 사라진다")
    void handsOffRevocationBeforeDeletingLocalCopy() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));

        service.disconnect(USER_ID);

        InOrder order = inOrder(tokenRevoker, disconnectWriter);
        order.verify(tokenRevoker).revokeGrantOrEnqueueToken(USER_ID, TOKEN);
        order.verify(disconnectWriter).disconnect(USER_ID);
    }

    @Test
    @DisplayName("토큰 하나가 아니라 authorization 전체를 폐기한다 — 단 이 자리에서 한 번만 시도한다")
    void revokesWholeAuthorization() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));
        given(tokenRevoker.revokeGrantOrEnqueueToken(USER_ID, TOKEN)).willReturn(true);

        GithubDisconnectResponse response = service.disconnect(USER_ID);

        assertThat(response.authorizationRevoked()).isTrue();
        verify(tokenRevoker).revokeGrantOrEnqueueToken(USER_ID, TOKEN);
    }

    @Test
    @DisplayName("로컬 정리는 한 트랜잭션에 맡긴다 — 토큰 삭제·상태 변경·분석 취소는 함께 커밋돼야 한다")
    void delegatesLocalCleanupToOneTransaction() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));

        service.disconnect(USER_ID);

        verify(disconnectWriter).disconnect(USER_ID);
        // 외부 폐기와 달리 이쪽은 여기서 쪼개지 않는다.
        verify(userTokenService, never()).delete(any());
    }

    @Test
    @DisplayName("폐기 인계 자체가 실패하면 연결 해제를 중단한다 — 회수 못 하는 토큰을 두고 성공이라 할 수 없다")
    void abortsWhenRevocationCannotBeHandedOff() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));
        willThrow(new DataAccessResourceFailureException("db down"))
                .given(tokenRevoker).revokeGrantOrEnqueueToken(anyLong(), anyString());

        assertThatThrownBy(() -> service.disconnect(USER_ID))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(disconnectWriter, never()).disconnect(any());
    }

    @Test
    @DisplayName("토큰이 없거나 만료됐으면 재인증을 요구하지 않고 직접 해제할 링크를 준다")
    void guidesManualRevokeWhenTokenIsGone() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.empty());

        GithubDisconnectResponse response = service.disconnect(USER_ID);

        verify(tokenRevoker, never()).revokeGrantOrEnqueueToken(anyLong(), anyString());
        verify(disconnectWriter).disconnect(USER_ID);
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
        given(tokenRevoker.revokeGrantOrEnqueueToken(USER_ID, TOKEN)).willReturn(false);

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

        verify(tokenRevoker, never()).revokeGrantOrEnqueueToken(anyLong(), anyString());
        verify(disconnectWriter, never()).disconnect(any());
    }

    private static GithubAppProperties properties() {
        return new GithubAppProperties(
                "12345", "galpi-app", "Iv1.client", "secret", "pem", "https://api.galpi.dev",
                "2022-11-28", "https://api.github.com", "https://github.com", "Galpi",
                List.of("https://galpi.dev"), "https://galpi.dev/auth/callback",
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2, 10);
    }
}

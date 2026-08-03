package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private GithubApiClient apiClient;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private GithubConnectionService service;

    @Test
    @DisplayName("GitHub에 토큰 폐기를 요청한 뒤 저장된 토큰을 지우고 DISCONNECTED로 바꾼다")
    void revokesAtGithubThenMarksDisconnected() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));

        service.disconnect(USER_ID);

        // 폐기는 로컬 삭제보다 먼저 일어나야 한다. 먼저 지우면 폐기할 토큰을 잃는다.
        InOrder order = inOrder(apiClient, userTokenService, userWriter);
        order.verify(apiClient).revokeUserToken(TOKEN);
        order.verify(userTokenService).delete(USER_ID);
        order.verify(userWriter).disconnectGithub(USER_ID);
    }

    @Test
    @DisplayName("GitHub 폐기가 실패해도 로컬 연결 해제는 끝까지 진행한다")
    void completesLocalDisconnectWhenRevokeFails() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));
        willThrow(new GithubApiException()).given(apiClient).revokeUserToken(TOKEN);

        assertThatCode(() -> service.disconnect(USER_ID)).doesNotThrowAnyException();

        verify(userTokenService).delete(USER_ID);
        verify(userWriter).disconnectGithub(USER_ID);
    }

    @Test
    @DisplayName("저장된 토큰이 없으면 폐기를 호출하지 않는다")
    void skipsRevokeWhenNoStoredToken() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.empty());

        service.disconnect(USER_ID);

        verify(apiClient, never()).revokeUserToken(any());
        verify(userWriter).disconnectGithub(USER_ID);
    }

    @Test
    @DisplayName("없는 회원이면 401이고 토큰도 건드리지 않는다")
    void rejectsUnknownUser() {
        given(userRepository.existsById(USER_ID)).willReturn(false);

        assertThatThrownBy(() -> service.disconnect(USER_ID))
                .isInstanceOf(UnauthorizedException.class)
                .extracting(e -> ((GlobalException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(apiClient, never()).revokeUserToken(any());
        verify(userTokenService, never()).delete(any());
        verify(userWriter, never()).disconnectGithub(any());
    }
}

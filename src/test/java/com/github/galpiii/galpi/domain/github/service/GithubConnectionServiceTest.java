package com.github.galpiii.galpi.domain.github.service;

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
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;

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

    @InjectMocks
    private GithubConnectionService service;

    @Test
    @DisplayName("로컬 원본을 지우기 전에 폐기를 넘긴다 — 순서가 뒤집히면 회수 수단이 사라진다")
    void handsOffRevocationBeforeDeletingLocalCopy() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));

        service.disconnect(USER_ID);

        InOrder order = inOrder(tokenRevoker, userTokenService, userWriter);
        order.verify(tokenRevoker).revokeOrEnqueue(USER_ID, TOKEN);
        order.verify(userTokenService).delete(USER_ID);
        order.verify(userWriter).disconnectGithub(USER_ID);
    }

    @Test
    @DisplayName("폐기 인계 자체가 실패하면 연결 해제를 중단한다 — 회수 못 하는 토큰을 두고 성공이라 할 수 없다")
    void abortsWhenRevocationCannotBeHandedOff() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.of(TOKEN));
        willThrow(new DataAccessResourceFailureException("db down"))
                .given(tokenRevoker).revokeOrEnqueue(anyLong(), anyString());

        assertThatThrownBy(() -> service.disconnect(USER_ID))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(userTokenService, never()).delete(any());
        verify(userWriter, never()).disconnectGithub(any());
    }

    @Test
    @DisplayName("저장된 토큰이 없으면 폐기를 호출하지 않는다")
    void skipsRevokeWhenNoStoredToken() {
        given(userRepository.existsById(USER_ID)).willReturn(true);
        given(userTokenService.find(USER_ID)).willReturn(Optional.empty());

        service.disconnect(USER_ID);

        verify(tokenRevoker, never()).revokeOrEnqueue(anyLong(), anyString());
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

        verify(tokenRevoker, never()).revokeOrEnqueue(anyLong(), anyString());
        verify(userTokenService, never()).delete(any());
        verify(userWriter, never()).disconnectGithub(any());
    }
}

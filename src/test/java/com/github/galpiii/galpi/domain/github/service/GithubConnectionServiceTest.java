package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.user.entity.GithubConnectionStatus;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("GithubConnectionService — 연결 해제")
class GithubConnectionServiceTest {

    private static final long USER_ID = 7L;

    @Mock
    private GithubUserTokenService userTokenService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private GithubConnectionService service;

    private static User userWithId(long id) {
        User user = User.ofGithub(999L, "octocat", "Octo", "dev@galpi.dev", "https://avatars/1");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    @DisplayName("저장된 GitHub 토큰을 폐기하고 연결 상태를 DISCONNECTED로 바꾼다")
    void revokesTokenAndMarksDisconnected() {
        User user = userWithId(USER_ID);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        service.disconnect(USER_ID);

        verify(userTokenService).delete(USER_ID);
        assertThat(user.getGithubConnectionStatus()).isEqualTo(GithubConnectionStatus.DISCONNECTED);
    }

    @Test
    @DisplayName("없는 회원이면 401이고 토큰도 건드리지 않는다")
    void rejectsUnknownUser() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.disconnect(USER_ID))
                .isInstanceOf(UnauthorizedException.class)
                .extracting(e -> ((GlobalException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(userTokenService, never()).delete(any());
    }
}

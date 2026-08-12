package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("GithubUserService — github_id 기준 회원 매핑")
class GithubUserServiceTest {

    private static final GithubUserResponse GITHUB_USER =
            new GithubUserResponse(999L, "octocat", "https://avatars/999", null, "Octo");

    @Mock
    private GithubUserWriter writer;

    @InjectMocks
    private GithubUserService service;

    private static User userWithId(long id) {
        User user = User.ofGithub(999L, "octocat", "Octo", null, "https://avatars/999");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    @DisplayName("첫 로그인이면 새 회원을 만든다")
    void createsUserOnFirstLogin() {
        User created = userWithId(1L);
        given(writer.updateExistingOrCreate(GITHUB_USER)).willReturn(created);

        assertThat(service.upsert(GITHUB_USER)).isSameAs(created);
    }

    @Test
    @DisplayName("같은 GitHub 계정으로 재로그인하면 기존 회원에 매핑된다")
    void mapsToExistingUserOnRelogin() {
        User existing = userWithId(1L);
        given(writer.updateExistingOrCreate(GITHUB_USER)).willReturn(existing);

        User first = service.upsert(GITHUB_USER);
        User second = service.upsert(GITHUB_USER);

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("동시 로그인으로 UNIQUE 충돌이 나면 기존 행을 재조회해 이어붙인다")
    void recoversFromUniqueViolation() {
        User winner = userWithId(1L);
        willThrow(new DataIntegrityViolationException("uk_users_github_id"))
                .given(writer).updateExistingOrCreate(GITHUB_USER);
        given(writer.updateExisting(GITHUB_USER)).willReturn(Optional.of(winner));

        User result = service.upsert(GITHUB_USER);

        assertThat(result).isSameAs(winner);
        verify(writer).updateExisting(GITHUB_USER);
    }

    @Test
    @DisplayName("충돌 후에도 회원을 찾지 못하면 로그인 실패로 처리한다")
    void failsWhenRecoveryFindsNothing() {
        willThrow(new DataIntegrityViolationException("conflict"))
                .given(writer).updateExistingOrCreate(GITHUB_USER);
        given(writer.updateExisting(GITHUB_USER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(GITHUB_USER))
                .isInstanceOf(UnauthorizedException.class)
                .extracting(e -> ((GlobalException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }
}

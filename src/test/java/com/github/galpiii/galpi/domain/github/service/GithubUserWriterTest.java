package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.user.entity.GithubConnectionStatus;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("GithubUserWriter — 회원 행 확보")
class GithubUserWriterTest {

    private static final long GITHUB_ID = 999L;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private GithubUserWriter writer;

    private static User existingUser() {
        User user = User.ofGithub(GITHUB_ID, "old-login", "Old Name", "old@galpi.dev", "https://avatars/old");
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    @Test
    @DisplayName("login이 바뀌어도 github_id로 찾아 같은 회원을 돌려준다 (로그인 실패 없음)")
    void findsByGithubIdOnRename() {
        User existing = existingUser();
        given(userRepository.findByGithubId(GITHUB_ID)).willReturn(Optional.of(existing));

        User result = writer.findOrCreate(new GithubUserResponse(
                GITHUB_ID, "new-login", "https://avatars/new", "new@galpi.dev", "New Name"));

        assertThat(result).isSameAs(existing);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("행 확보는 프로필을 건드리지 않는다 — 갱신은 연결 트랜잭션의 몫이다")
    void doesNotTouchProfile() {
        User existing = existingUser();
        given(userRepository.findByGithubId(GITHUB_ID)).willReturn(Optional.of(existing));

        User result = writer.findOrCreate(new GithubUserResponse(
                GITHUB_ID, "new-login", "https://avatars/new", "new@galpi.dev", "New Name"));

        assertThat(result.getLogin()).isEqualTo("old-login");
        assertThat(result.getAvatarUrl()).isEqualTo("https://avatars/old");
    }

    @Test
    @DisplayName("행 확보만으로는 연결이 되지 않는다 — 토큰이 저장되기 전에는 DISCONNECTED다")
    void doesNotConnect() {
        User existing = existingUser();
        existing.disconnectGithub();
        given(userRepository.findByGithubId(GITHUB_ID)).willReturn(Optional.of(existing));

        User result = writer.findOrCreate(new GithubUserResponse(
                GITHUB_ID, "octocat", "https://avatars/999", null, "Octo"));

        assertThat(result.getGithubConnectionStatus())
                .isEqualTo(GithubConnectionStatus.DISCONNECTED);
    }

    @Test
    @DisplayName("처음 보는 github_id면 새 회원을 만들되 연결로 확정하지는 않는다")
    void createsWhenAbsent() {
        given(userRepository.findByGithubId(GITHUB_ID)).willReturn(Optional.empty());
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(call -> call.getArgument(0));

        writer.findOrCreate(new GithubUserResponse(
                GITHUB_ID, "octocat", "https://avatars/999", "dev@galpi.dev", "Octo"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getGithubId()).isEqualTo(GITHUB_ID);
        // 토큰 저장이 끝나야 연결이다. 여기서 CONNECTED로 만들면 저장 실패 시 그대로 남는다.
        assertThat(saved.getValue().getGithubConnectionStatus())
                .isEqualTo(GithubConnectionStatus.DISCONNECTED);
        assertThat(saved.getValue().getConnectedAt()).isNull();
    }
}

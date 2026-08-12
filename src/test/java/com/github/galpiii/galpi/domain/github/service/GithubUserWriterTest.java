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
@DisplayName("GithubUserWriter — 프로필 스냅샷 갱신")
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
    @DisplayName("login이 바뀌어도 github_id로 찾아 기존 회원을 갱신한다 (로그인 실패 없음)")
    void updatesLoginOnRename() {
        User existing = existingUser();
        given(userRepository.findByGithubId(GITHUB_ID)).willReturn(Optional.of(existing));

        User result = writer.updateExistingOrCreate(new GithubUserResponse(
                GITHUB_ID, "new-login", "https://avatars/new", "new@galpi.dev", "New Name"));

        assertThat(result).isSameAs(existing);
        assertThat(result.getLogin()).isEqualTo("new-login");
        assertThat(result.getName()).isEqualTo("New Name");
        assertThat(result.getAvatarUrl()).isEqualTo("https://avatars/new");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("이메일이 비공개로 바뀌어 null로 와도 기존 값을 지우지 않는다")
    void keepsEmailWhenGithubHidesIt() {
        User existing = existingUser();
        given(userRepository.findByGithubId(GITHUB_ID)).willReturn(Optional.of(existing));

        User result = writer.updateExistingOrCreate(new GithubUserResponse(
                GITHUB_ID, "octocat", "https://avatars/999", null, "Octo"));

        assertThat(result.getEmail()).isEqualTo("old@galpi.dev");
    }

    @Test
    @DisplayName("연결이 해제된 회원이 재로그인하면 CONNECTED로 되돌린다")
    void reconnectsDisconnectedUser() {
        User existing = existingUser();
        existing.disconnectGithub();
        given(userRepository.findByGithubId(GITHUB_ID)).willReturn(Optional.of(existing));

        User result = writer.updateExistingOrCreate(new GithubUserResponse(
                GITHUB_ID, "octocat", "https://avatars/999", null, "Octo"));

        assertThat(result.getGithubConnectionStatus()).isEqualTo(GithubConnectionStatus.CONNECTED);
    }

    @Test
    @DisplayName("처음 보는 github_id면 새 회원을 만든다")
    void createsWhenAbsent() {
        given(userRepository.findByGithubId(GITHUB_ID)).willReturn(Optional.empty());
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(call -> call.getArgument(0));

        writer.updateExistingOrCreate(new GithubUserResponse(
                GITHUB_ID, "octocat", "https://avatars/999", "dev@galpi.dev", "Octo"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getGithubId()).isEqualTo(GITHUB_ID);
        assertThat(saved.getValue().getGithubConnectionStatus())
                .isEqualTo(GithubConnectionStatus.CONNECTED);
    }
}

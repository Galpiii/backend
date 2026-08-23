package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.user.entity.GithubConnectionStatus;
import com.github.galpiii.galpi.domain.user.entity.OAuthProvider;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserOAuthTokenRepository;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * 연결이 "토큰과 상태가 함께" 확정되는지 확인한다.
 *
 * <p>둘이 갈라지면 두 가지 반쪽 상태가 생긴다. CONNECTED인데 토큰이 없으면 사용자가 아무것도
 * 못 하고, DISCONNECTED인데 토큰이 살아 있으면 상태가 아니라 토큰만 보는 경로가 그것으로
 * GitHub을 계속 부른다. 뒤쪽이 위험해서 이 테스트가 있다.
 */
@DisplayName("GitHub 연결 확정 — 실제 Postgres")
class GithubConnectIntegrationTest extends IntegrationTestSupport {

    private static final String TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz012345";

    @Autowired
    private GithubConnectWriter connectWriter;
    @Autowired
    private GithubUserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserOAuthTokenRepository tokenRepository;

    @MockitoBean
    private GithubApiClient apiClient;
    @MockitoSpyBean
    private GithubUserTokenService userTokenService;

    @Test
    @DisplayName("행만 확보한 단계에서는 아직 연결이 아니다")
    void findOrCreateDoesNotConnect() {
        User user = userService.findOrCreate(initialGithubUser());

        assertThat(userRepository.findById(user.getId()).orElseThrow()
                .getGithubConnectionStatus()).isEqualTo(GithubConnectionStatus.DISCONNECTED);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getConnectedAt()).isNull();
    }

    @Test
    @DisplayName("토큰 저장과 상태 전이가 함께 커밋된다")
    void connectsTokenAndStatusTogether() {
        User user = userService.findOrCreate(initialGithubUser());

        connectWriter.connect(user.getId(), githubUser(), TOKEN, Duration.ofHours(8));

        User connected = userRepository.findById(user.getId()).orElseThrow();
        assertThat(connected.getGithubConnectionStatus())
                .isEqualTo(GithubConnectionStatus.CONNECTED);
        assertThat(connected.getConnectedAt()).isNotNull();
        // 프로필 갱신도 같은 트랜잭션에서 이뤄진다.
        assertThat(connected.getLogin()).isEqualTo("octocat");
        assertThat(tokenRepository.findByUserIdAndProvider(user.getId(), OAuthProvider.GITHUB))
                .isPresent();
    }

    @Test
    @DisplayName("토큰 저장이 실패하면 연결로 확정되지 않는다 — 토큰 없는 CONNECTED를 남기지 않는다")
    void doesNotConnectWhenTokenStorageFails() {
        User user = userService.findOrCreate(initialGithubUser());
        willThrow(new DataAccessResourceFailureException("db down"))
                .given(AopTestUtils.<GithubUserTokenService>getUltimateTargetObject(userTokenService))
                .save(any(), any(), any());

        assertThatThrownBy(() ->
                connectWriter.connect(user.getId(), githubUser(), TOKEN, Duration.ofHours(8)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        User stored = userRepository.findById(user.getId()).orElseThrow();
        assertThat(stored.getGithubConnectionStatus())
                .isEqualTo(GithubConnectionStatus.DISCONNECTED);
        assertThat(stored.getConnectedAt()).isNull();
        // 프로필 갱신도 함께 되돌아간다. 연결 트랜잭션은 통째로 하나다.
        assertThat(stored.getLogin()).isEqualTo("before-login");
        assertThat(tokenRepository.findByUserIdAndProvider(user.getId(), OAuthProvider.GITHUB))
                .isEmpty();
    }

    @Test
    @DisplayName("연결이 끊긴 회원이 다시 로그인하면 같은 트랜잭션에서 되살아난다")
    void reconnectsDisconnectedUser() {
        User user = userService.findOrCreate(initialGithubUser());
        connectWriter.connect(user.getId(), githubUser(), TOKEN, Duration.ofHours(8));
        userRepository.findById(user.getId()).ifPresent(stored -> {
            stored.disconnectGithub();
            userRepository.saveAndFlush(stored);
        });

        connectWriter.connect(user.getId(), githubUser(), "ghu_second_token_second_token_0001",
                Duration.ofHours(8));

        assertThat(userRepository.findById(user.getId()).orElseThrow()
                .getGithubConnectionStatus()).isEqualTo(GithubConnectionStatus.CONNECTED);
    }

    /** 행이 처음 만들어질 때의 프로필. 연결 시점 값과 달라야 갱신·롤백을 구분할 수 있다. */
    private static GithubUserResponse initialGithubUser() {
        return new GithubUserResponse(4242L, "before-login", "https://avatars/before",
                "dev@galpi.dev", "Before");
    }

    private static GithubUserResponse githubUser() {
        return new GithubUserResponse(4242L, "octocat", "https://avatars/4242",
                "dev@galpi.dev", "Octo");
    }
}

package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.auth.config.JwtProperties;
import com.github.galpiii.galpi.domain.github.exception.GithubReauthRequiredException;
import com.github.galpiii.galpi.domain.github.store.GithubUserTokenCache;
import com.github.galpiii.galpi.domain.user.entity.OAuthProvider;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.entity.UserOAuthToken;
import com.github.galpiii.galpi.domain.user.repository.UserOAuthTokenRepository;
import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenEncryptionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GithubUserTokenService — DB 원본 + Redis 캐시")
class GithubUserTokenServiceTest {

    private static final long USER_ID = 7L;
    private static final String TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz012345";
    private static final Duration SESSION_TTL = Duration.ofDays(14);

    @Mock
    private UserOAuthTokenRepository tokenRepository;
    @Mock
    private GithubUserTokenCache cache;
    @Mock
    private GithubTokenRevoker tokenRevoker;

    private TokenCipher tokenCipher;
    private GithubUserTokenService service;

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static JwtProperties jwtProperties() {
        return new JwtProperties(
                "galpi-test-secret-key-must-be-at-least-32-bytes", "galpi",
                Duration.ofMinutes(30), SESSION_TTL, Duration.ofDays(90), Duration.ofSeconds(60),
                new JwtProperties.Cookie("galpi_refresh", "/auth", true, "Lax", ""));
    }

    private static User user() {
        User user = User.ofGithub(999L, "octocat", "Octo", null, "https://avatars/999");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private UserOAuthToken storedToken(String plaintext, OffsetDateTime expiresAt) {
        return UserOAuthToken.issue(user(), OAuthProvider.GITHUB,
                tokenCipher.encrypt(plaintext), expiresAt, tokenCipher.currentVersion());
    }

    @BeforeEach
    void setUp() {
        tokenCipher = new TokenCipher(new TokenEncryptionProperties(1, Map.of(1, randomKey())));
        service = new GithubUserTokenService(
                tokenRepository, cache, tokenCipher, jwtProperties(), tokenRevoker);
    }

    @Nested
    @DisplayName("저장")
    class Save {

        @Test
        @DisplayName("평문이 아니라 암호문을 저장한다")
        void storesEncrypted() {
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.empty());

            service.save(user(), TOKEN, Duration.ofHours(8));

            ArgumentCaptor<UserOAuthToken> saved = ArgumentCaptor.forClass(UserOAuthToken.class);
            verify(tokenRepository).save(saved.capture());
            assertThat(saved.getValue().getEncryptedAccessToken()).doesNotContain(TOKEN);
            assertThat(tokenCipher.decrypt(
                    saved.getValue().getEncryptedAccessToken(), saved.getValue().getTokenVersion()))
                    .isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("expires_in으로 만료 시각을 계산해 저장한다")
        void storesExpiry() {
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.empty());

            service.save(user(), TOKEN, Duration.ofHours(8));

            ArgumentCaptor<UserOAuthToken> saved = ArgumentCaptor.forClass(UserOAuthToken.class);
            verify(tokenRepository).save(saved.capture());
            assertThat(saved.getValue().getAccessTokenExpiresAt())
                    .isCloseTo(OffsetDateTime.now().plusHours(8), within(10, ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("expires_in이 없으면 만료 시각을 NULL로 둔다 (App 만료 설정 비활성)")
        void storesNullExpiryWhenAbsent() {
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.empty());

            service.save(user(), TOKEN, null);

            ArgumentCaptor<UserOAuthToken> saved = ArgumentCaptor.forClass(UserOAuthToken.class);
            verify(tokenRepository).save(saved.capture());
            assertThat(saved.getValue().getAccessTokenExpiresAt()).isNull();
        }

        @Test
        @DisplayName("토큰 교체 시 행을 새로 만들지 않고 기존 행을 덮어쓴다")
        void replacesInsteadOfInserting() {
            UserOAuthToken existing = storedToken("ghu_old", OffsetDateTime.now().plusHours(1));
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.of(existing));

            service.save(user(), TOKEN, Duration.ofHours(8));

            verify(tokenRepository, never()).save(any());
            assertThat(tokenCipher.decrypt(existing.getEncryptedAccessToken(), existing.getTokenVersion()))
                    .isEqualTo(TOKEN);
        }

        @Test
        @DisplayName("덮어쓰기 전에 이전 암호문을 폐기 큐로 넘긴다 — 재로그인해도 이전 토큰은 GitHub에 살아 있다")
        void enqueuesSupersededTokenBeforeReplacing() {
            UserOAuthToken existing = storedToken("ghu_old", OffsetDateTime.now().plusHours(1));
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.of(existing));

            service.save(user(), TOKEN, Duration.ofHours(8));

            ArgumentCaptor<String> ciphertext = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> version = ArgumentCaptor.forClass(Integer.class);
            verify(tokenRevoker).enqueueSuperseded(eq(USER_ID), ciphertext.capture(), version.capture());
            assertThat(tokenCipher.decrypt(ciphertext.getValue(), version.getValue()))
                    .isEqualTo("ghu_old");
        }

        @Test
        @DisplayName("이미 만료된 이전 토큰은 큐에 넣지 않는다 — 폐기할 것이 없다")
        void skipsEnqueueWhenSupersededTokenAlreadyExpired() {
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.of(
                            storedToken("ghu_old", OffsetDateTime.now().minusMinutes(1))));

            service.save(user(), TOKEN, Duration.ofHours(8));

            verify(tokenRevoker, never()).enqueueSuperseded(any(), any(), anyInt());
        }

        @Test
        @DisplayName("첫 로그인이면 폐기할 이전 토큰이 없다")
        void skipsEnqueueOnFirstLogin() {
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.empty());

            service.save(user(), TOKEN, Duration.ofHours(8));

            verify(tokenRevoker, never()).enqueueSuperseded(any(), any(), anyInt());
        }

        @Test
        @DisplayName("캐시 TTL은 GitHub 만료와 세션 수명 중 짧은 쪽이다")
        void cachesWithShorterTtl() {
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.empty());

            service.save(user(), TOKEN, Duration.ofHours(8));

            ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
            verify(cache).put(eq(USER_ID), eq(TOKEN), ttl.capture());
            assertThat(ttl.getValue()).isLessThanOrEqualTo(Duration.ofHours(8));
            assertThat(ttl.getValue()).isGreaterThan(Duration.ofHours(7));
        }

        @Test
        @DisplayName("GitHub 만료가 세션보다 길면 세션 수명으로 자른다")
        void capsCacheTtlAtSessionLifetime() {
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.empty());

            service.save(user(), TOKEN, SESSION_TTL.plusDays(30));

            verify(cache).put(USER_ID, TOKEN, SESSION_TTL);
        }

        @Test
        @DisplayName("캐시는 커밋된 뒤에 채운다 — 롤백되면 넣지 않는다")
        void fillsCacheOnlyAfterCommit() {
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.empty());
            TransactionSynchronizationManager.initSynchronization();
            try {
                service.save(user(), TOKEN, Duration.ofHours(8));

                verify(cache, never()).put(any(), any(), any());

                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(TransactionSynchronization::afterCommit);

                verify(cache).put(eq(USER_ID), eq(TOKEN), any());
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    @Nested
    @DisplayName("조회")
    class Find {

        @Test
        @DisplayName("캐시에 있으면 DB를 읽지 않는다")
        void servesFromCache() {
            given(cache.find(USER_ID)).willReturn(Optional.of(TOKEN));

            assertThat(service.find(USER_ID)).contains(TOKEN);
            verify(tokenRepository, never()).findByUserIdAndProvider(any(), any());
        }

        @Test
        @DisplayName("캐시 미스면 DB에서 복호화해 캐시를 다시 채운다")
        void refillsCacheOnMiss() {
            given(cache.find(USER_ID)).willReturn(Optional.empty());
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.of(storedToken(TOKEN, OffsetDateTime.now().plusHours(8))));

            assertThat(service.find(USER_ID)).contains(TOKEN);
            verify(cache).put(eq(USER_ID), eq(TOKEN), any());
        }

        @Test
        @DisplayName("만료된 토큰은 없는 것으로 취급한다")
        void treatsExpiredAsAbsent() {
            given(cache.find(USER_ID)).willReturn(Optional.empty());
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.of(storedToken(TOKEN, OffsetDateTime.now().minusMinutes(1))));

            assertThat(service.find(USER_ID)).isEmpty();
            verify(cache, never()).put(any(), any(), any());
        }

        @Test
        @DisplayName("복호화에 실패하면 오류를 퍼뜨리지 않고 재연결 대상으로 만든다")
        void degradesGracefullyOnDecryptFailure() {
            UserOAuthToken corrupted = UserOAuthToken.issue(
                    user(), OAuthProvider.GITHUB, "not-base64!!", OffsetDateTime.now().plusHours(8), 1);
            given(cache.find(USER_ID)).willReturn(Optional.empty());
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.of(corrupted));

            assertThat(service.find(USER_ID)).isEmpty();
            assertThat(service.isValid(USER_ID)).isFalse();
        }

        @Test
        @DisplayName("토큰이 없으면 require는 재연결을 요구한다")
        void requireThrowsWhenAbsent() {
            given(cache.find(USER_ID)).willReturn(Optional.empty());
            given(tokenRepository.findByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.require(USER_ID))
                    .isInstanceOf(GithubReauthRequiredException.class);
        }
    }

    @Nested
    @DisplayName("삭제")
    class Delete {

        @Test
        @DisplayName("로그아웃 시 캐시와 원본을 함께 지운다")
        void removesBoth() {
            service.delete(USER_ID);

            verify(cache).evict(USER_ID);
            verify(tokenRepository).deleteByUserIdAndProvider(USER_ID, OAuthProvider.GITHUB);
        }
    }
}

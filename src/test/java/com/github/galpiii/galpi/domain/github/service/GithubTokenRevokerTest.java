package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.repository.GithubTokenRevocationRepository;
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
import org.springframework.data.domain.Limit;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("GithubTokenRevoker — 폐기 실패는 잊지 않는다")
class GithubTokenRevokerTest {

    private static final long USER_ID = 7L;
    private static final String TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz012345";

    @Mock
    private GithubApiClient apiClient;
    @Mock
    private GithubTokenRevocationRepository revocationRepository;

    private TokenCipher tokenCipher;
    private GithubTokenRevoker revoker;

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @BeforeEach
    void setUp() {
        tokenCipher = new TokenCipher(new TokenEncryptionProperties(1, Map.of(1, randomKey())));
        revoker = new GithubTokenRevoker(apiClient, revocationRepository, tokenCipher);
    }

    private GithubTokenRevocation pendingFor(String token) {
        return GithubTokenRevocation.pending(
                USER_ID, tokenCipher.encrypt(token), tokenCipher.currentVersion(), "GithubApiException");
    }

    private void givenDue(GithubTokenRevocation... rows) {
        given(revocationRepository
                .findByAttemptsLessThanAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        anyInt(), any(OffsetDateTime.class), any(Limit.class)))
                .willReturn(List.of(rows));
    }

    @Nested
    @DisplayName("연결 해제 시점")
    class OnDisconnect {

        @Test
        @DisplayName("폐기에 성공하면 큐에 남기지 않는다")
        void doesNotEnqueueOnSuccess() {
            revoker.revokeOrEnqueue(USER_ID, TOKEN);

            verify(apiClient).revokeUserToken(TOKEN);
            verify(revocationRepository, never()).save(any());
        }

        @Test
        @DisplayName("폐기에 실패하면 재시도 큐에 남긴다 — 외부 토큰을 잊어버리면 안 된다")
        void enqueuesOnFailure() {
            willThrow(new GithubApiException()).given(apiClient).revokeUserToken(TOKEN);

            revoker.revokeOrEnqueue(USER_ID, TOKEN);

            ArgumentCaptor<GithubTokenRevocation> saved =
                    ArgumentCaptor.forClass(GithubTokenRevocation.class);
            verify(revocationRepository).save(saved.capture());
            assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getValue().getAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("큐에 남기는 토큰도 암호문이다 — 평문이 DB에 눕지 않는다")
        void enqueuesCiphertextOnly() {
            willThrow(new GithubApiException()).given(apiClient).revokeUserToken(TOKEN);

            revoker.revokeOrEnqueue(USER_ID, TOKEN);

            ArgumentCaptor<GithubTokenRevocation> saved =
                    ArgumentCaptor.forClass(GithubTokenRevocation.class);
            verify(revocationRepository).save(saved.capture());
            assertThat(saved.getValue().getEncryptedAccessToken())
                    .doesNotContain(TOKEN)
                    .doesNotContain("ghu_");
        }
    }

    @Nested
    @DisplayName("재시도 배치")
    class Retry {

        @Test
        @DisplayName("성공하면 원래 토큰으로 폐기하고 큐에서 지운다")
        void revokesWithOriginalTokenAndClears() {
            GithubTokenRevocation pending = pendingFor(TOKEN);
            givenDue(pending);

            assertThat(revoker.retryPending()).isEqualTo(1);

            verify(apiClient).revokeUserToken(TOKEN);
            verify(revocationRepository).delete(pending);
        }

        @Test
        @DisplayName("또 실패하면 시도 횟수를 올리고 큐에 남겨 둔다")
        void keepsRowAndBacksOffOnFailure() {
            GithubTokenRevocation pending = pendingFor(TOKEN);
            givenDue(pending);
            willThrow(new GithubApiException()).given(apiClient).revokeUserToken(anyString());

            assertThat(revoker.retryPending()).isZero();

            assertThat(pending.getAttempts()).isEqualTo(2);
            verify(revocationRepository, never()).delete(any());
        }

        @Test
        @DisplayName("실패할수록 다음 시도를 뒤로 민다")
        void backsOffProgressively() {
            GithubTokenRevocation pending = pendingFor(TOKEN);
            givenDue(pending);
            willThrow(new GithubApiException()).given(apiClient).revokeUserToken(anyString());

            revoker.retryPending();
            OffsetDateTime afterFirst = pending.getNextAttemptAt();
            revoker.retryPending();

            assertThat(pending.getNextAttemptAt()).isAfter(afterFirst);
        }

        @Test
        @DisplayName("복호화할 수 없게 된 항목은 붙잡고 있지 않는다")
        void dropsUndecryptableRow() {
            GithubTokenRevocation broken = GithubTokenRevocation.pending(USER_ID, "not-base64!!", 1, "x");
            givenDue(broken);

            assertThat(revoker.retryPending()).isZero();

            verify(apiClient, never()).revokeUserToken(anyString());
            verify(revocationRepository).delete(broken);
        }

        @Test
        @DisplayName("밀린 게 없으면 아무것도 하지 않는다")
        void doesNothingWhenQueueEmpty() {
            givenDue();

            assertThat(revoker.retryPending()).isZero();

            verify(apiClient, never()).revokeUserToken(anyString());
        }
    }
}

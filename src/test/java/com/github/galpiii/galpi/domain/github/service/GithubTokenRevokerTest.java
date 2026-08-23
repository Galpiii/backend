package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.entity.GithubRevocationType;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.Limit;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock
    private GithubTokenRevocationWriter writer;

    private GithubTokenRevoker revoker;

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @BeforeEach
    void setUp() {
        TokenCipher tokenCipher = new TokenCipher(new TokenEncryptionProperties(1, Map.of(1, randomKey())));
        revoker = new GithubTokenRevoker(apiClient, revocationRepository, writer, tokenCipher);
    }

    @Nested
    @DisplayName("연결 해제 시점")
    class OnDisconnect {

        @Test
        @DisplayName("폐기에 성공하면 큐에 남기지 않는다")
        void doesNotEnqueueOnSuccess() {
            revoker.revokeOrEnqueue(USER_ID, TOKEN, GithubRevocationType.GRANT);

            verify(apiClient).revokeUserGrant(TOKEN);
            verify(writer, never()).enqueueFailed(anyLong(), any(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("폐기에 실패하면 재시도 큐에 남긴다 — 외부 토큰을 잊어버리면 안 된다")
        void enqueuesOnFailure() {
            willThrow(new GithubApiException()).given(apiClient).revokeUserGrant(TOKEN);

            revoker.revokeOrEnqueue(USER_ID, TOKEN, GithubRevocationType.GRANT);

            verify(writer).enqueueFailed(eq(USER_ID), any(), anyInt(),
                    eq(GithubRevocationType.GRANT), eq("GithubApiException"));
        }

        @Test
        @DisplayName("큐 적재는 GitHub 호출과 분리된 트랜잭션에 맡긴다 — 외부 응답을 트랜잭션 안에서 기다리지 않는다")
        void keepsHttpCallOutsideTransaction() {
            willThrow(new GithubApiException()).given(apiClient).revokeUserGrant(TOKEN);

            revoker.revokeOrEnqueue(USER_ID, TOKEN, GithubRevocationType.GRANT);

            // 성공 경로에는 DB 쓰기가 없고, 실패 경로만 짧은 쓰기 트랜잭션으로 넘어간다.
            verify(revocationRepository, never()).save(any());
            verify(writer).enqueueFailed(anyLong(), any(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("큐에 남기는 토큰도 암호문이다 — 평문이 DB에 눕지 않는다")
        void enqueuesCiphertextOnly() {
            willThrow(new GithubApiException()).given(apiClient).revokeUserGrant(TOKEN);

            revoker.revokeOrEnqueue(USER_ID, TOKEN, GithubRevocationType.GRANT);

            ArgumentCaptor<String> ciphertext = ArgumentCaptor.forClass(String.class);
            verify(writer).enqueueFailed(eq(USER_ID), ciphertext.capture(), anyInt(), any(), any());
            assertThat(ciphertext.getValue())
                    .doesNotContain(TOKEN)
                    .doesNotContain("ghu_");
        }
    }

    @Nested
    @DisplayName("재연결 시점")
    class OnReconnect {

        @Test
        @DisplayName("밀려 있던 grant 폐기를 버린다 — 다시 승인한 authorization을 폐기할 수는 없다")
        void discardsPendingGrants() {
            given(revocationRepository.deleteByUserIdAndRevocationType(
                    USER_ID, GithubRevocationType.GRANT)).willReturn(1);

            revoker.discardPendingGrants(USER_ID);

            verify(revocationRepository)
                    .deleteByUserIdAndRevocationType(USER_ID, GithubRevocationType.GRANT);
        }

        @Test
        @DisplayName("밀려난 토큰 폐기는 남긴다 — 이전 토큰은 재연결과 무관하게 회수해야 한다")
        void keepsSupersededTokenRows() {
            revoker.discardPendingGrants(USER_ID);

            verify(revocationRepository, never())
                    .deleteByUserIdAndRevocationType(USER_ID, GithubRevocationType.TOKEN);
        }
    }

    @Nested
    @DisplayName("재로그인 시점")
    class OnRelogin {

        @Test
        @DisplayName("GitHub을 부르지 않고 큐에만 넣는다 — 로그인을 기다리게 하지 않는다")
        void enqueuesWithoutCallingGithub() {
            revoker.enqueueSuperseded(USER_ID, "ciphertext", 1);

            verify(apiClient, never()).revokeUserToken(any());
            verify(revocationRepository).save(any());
        }

        @Test
        @DisplayName("시도한 적 없는 건으로 남긴다 — 실패 건과 구분되고 다음 회차에 바로 나간다")
        void marksAsNeverAttempted() {
            revoker.enqueueSuperseded(USER_ID, "ciphertext", 2);

            ArgumentCaptor<GithubTokenRevocation> saved =
                    ArgumentCaptor.forClass(GithubTokenRevocation.class);
            verify(revocationRepository).save(saved.capture());
            assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getValue().getEncryptedAccessToken()).isEqualTo("ciphertext");
            assertThat(saved.getValue().getTokenVersion()).isEqualTo(2);
            assertThat(saved.getValue().getAttempts()).isZero();
            assertThat(saved.getValue().getLastError()).isNull();
            assertThat(saved.getValue().getNextAttemptAt())
                    .isBeforeOrEqualTo(OffsetDateTime.now());
        }
    }

    @Nested
    @DisplayName("재시도 배치")
    class Retry {

        @Test
        @DisplayName("밀린 건마다 한 번씩 처리하고 성공 건수를 센다")
        void processesEveryDueRow() {
            given(writer.findDueIds(any(Limit.class))).willReturn(List.of(1L, 2L, 3L));
            given(writer.revokeOne(1L)).willReturn(true);
            given(writer.revokeOne(2L)).willReturn(false);
            given(writer.revokeOne(3L)).willReturn(true);

            assertThat(revoker.retryPending()).isEqualTo(2);
        }

        @Test
        @DisplayName("한 건이 터져도 나머지는 계속 처리한다 — 배치가 통째로 멈추면 안 된다")
        void oneBrokenRowDoesNotStopTheBatch() {
            given(writer.findDueIds(any(Limit.class))).willReturn(List.of(1L, 2L, 3L));
            given(writer.revokeOne(1L)).willThrow(new CannotAcquireLockException("commit failed"));
            given(writer.revokeOne(2L)).willReturn(true);
            given(writer.revokeOne(3L)).willReturn(true);

            assertThat(revoker.retryPending()).isEqualTo(2);

            verify(writer).revokeOne(3L);
        }

        @Test
        @DisplayName("밀린 게 없으면 아무것도 하지 않는다")
        void doesNothingWhenQueueEmpty() {
            given(writer.findDueIds(any(Limit.class))).willReturn(List.of());

            assertThat(revoker.retryPending()).isZero();

            verify(writer, never()).revokeOne(anyLong());
        }
    }
}

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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("GithubTokenRevoker — 폐기 실패는 잊지 않되, 큐에는 토큰 폐기만 남는다")
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
    private TokenCipher tokenCipher;
    private TokenCipher otherCipher;

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @BeforeEach
    void setUp() {
        tokenCipher = new TokenCipher(new TokenEncryptionProperties(1, Map.of(1, randomKey())));
        // 키 버전이 같아도 키가 다르면 복호화가 실패한다. 키 설정 누락과 같은 상황이다.
        otherCipher = new TokenCipher(new TokenEncryptionProperties(1, Map.of(1, randomKey())));
        revoker = new GithubTokenRevoker(apiClient, revocationRepository, writer, tokenCipher);
    }

    @Nested
    @DisplayName("연결 해제 시점")
    class OnDisconnect {

        private static final long REVOCATION_ID = 31L;

        private ArgumentCaptor<GithubTokenRevocation> enqueue() {
            ArgumentCaptor<GithubTokenRevocation> saved =
                    ArgumentCaptor.forClass(GithubTokenRevocation.class);
            given(revocationRepository.save(saved.capture())).willAnswer(call -> {
                GithubTokenRevocation row = call.getArgument(0);
                ReflectionTestUtils.setField(row, "id", REVOCATION_ID);
                return row;
            });
            return saved;
        }

        @Test
        @DisplayName("GitHub을 부르기 전에 폐기 의도를 남긴다 — 호출자의 트랜잭션에 참여한다")
        void enqueuesIntentWithoutCallingGithub() {
            enqueue();

            assertThat(revoker.enqueueDisconnectIntent(USER_ID, TOKEN)).isEqualTo(REVOCATION_ID);

            // 짧은 트랜잭션을 따로 여는 writer가 아니라 리포지토리를 그대로 쓴다.
            verify(apiClient, never()).revokeUserGrant(any());
            verify(revocationRepository).save(any());
        }

        @Test
        @DisplayName("큐에 남기는 토큰도 암호문이고, 시도한 적 없는 건으로 남는다")
        void enqueuesCiphertextAsNeverAttempted() {
            ArgumentCaptor<GithubTokenRevocation> saved = enqueue();

            revoker.enqueueDisconnectIntent(USER_ID, TOKEN);

            assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getValue().getAttempts()).isZero();
            assertThat(saved.getValue().getEncryptedAccessToken())
                    .doesNotContain(TOKEN)
                    .doesNotContain("ghu_");
            // 커밋 직후의 grant 폐기가 먼저 끝나도록 첫 시도를 조금 미룬다.
            assertThat(saved.getValue().getNextAttemptAt()).isAfter(OffsetDateTime.now());
        }

        @Test
        @DisplayName("authorization 폐기에 성공하면 남겨 둔 의도를 지운다")
        void discardsIntentOnSuccess() {
            assertThat(revoker.revokeGrantAfterDisconnect(USER_ID, TOKEN, REVOCATION_ID)).isTrue();

            verify(apiClient).revokeUserGrant(TOKEN);
            verify(writer).discard(REVOCATION_ID);
        }

        @Test
        @DisplayName("실패하면 의도를 그대로 둔다 — 배치가 토큰 하나만 폐기한다")
        void keepsIntentOnFailure() {
            willThrow(new GithubApiException()).given(apiClient).revokeUserGrant(TOKEN);

            assertThat(revoker.revokeGrantAfterDisconnect(USER_ID, TOKEN, REVOCATION_ID)).isFalse();

            verify(writer, never()).discard(anyLong());
        }

        @Test
        @DisplayName("의도를 지우지 못해도 폐기 성공을 실패로 뒤집지 않는다 — 배치가 죽은 토큰을 한 번 더 부를 뿐이다")
        void survivesFailureToDiscardIntent() {
            willThrow(new CannotAcquireLockException("delete failed"))
                    .given(writer).discard(REVOCATION_ID);

            assertThat(revoker.revokeGrantAfterDisconnect(USER_ID, TOKEN, REVOCATION_ID)).isTrue();
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

        private static final long ROW_ID = 11L;

        private void due(Long... ids) {
            given(writer.findDueIds(any(Limit.class))).willReturn(List.of(ids));
        }

        private void loads(Long id, String ciphertext, int version) {
            given(writer.load(id)).willReturn(Optional.of(
                    new GithubTokenRevocationWriter.PendingRevocation(id, USER_ID, ciphertext,
                            version, 1)));
        }

        @Test
        @DisplayName("밀린 건마다 한 번씩 처리하고 성공 건수를 센다")
        void processesEveryDueRow() {
            due(1L, 2L, 3L);
            loads(1L, tokenCipher.encrypt(TOKEN), tokenCipher.currentVersion());
            loads(3L, tokenCipher.encrypt(TOKEN), tokenCipher.currentVersion());
            // 2번은 이미 다른 인스턴스가 끝냈다.
            given(writer.load(2L)).willReturn(Optional.empty());

            assertThat(revoker.retryPending()).isEqualTo(2);
        }

        @Test
        @DisplayName("한 건이 터져도 나머지는 계속 처리한다 — 배치가 통째로 멈추면 안 된다")
        void oneBrokenRowDoesNotStopTheBatch() {
            due(1L, 2L, 3L);
            given(writer.load(1L)).willThrow(new CannotAcquireLockException("read failed"));
            loads(2L, tokenCipher.encrypt(TOKEN), tokenCipher.currentVersion());
            loads(3L, tokenCipher.encrypt(TOKEN), tokenCipher.currentVersion());

            assertThat(revoker.retryPending()).isEqualTo(2);

            verify(writer).load(3L);
        }

        @Test
        @DisplayName("밀린 게 없으면 아무것도 하지 않는다")
        void doesNothingWhenQueueEmpty() {
            given(writer.findDueIds(any(Limit.class))).willReturn(List.of());

            assertThat(revoker.retryPending()).isZero();

            verify(writer, never()).load(anyLong());
        }

        @Test
        @DisplayName("GitHub 호출은 트랜잭션 밖이다 — 읽기와 결과 기록만 짧게 잡는다")
        void callsGithubBetweenTwoShortTransactions() {
            due(ROW_ID);
            loads(ROW_ID, tokenCipher.encrypt(TOKEN), tokenCipher.currentVersion());

            revoker.retryPending();

            InOrder order = inOrder(writer, apiClient);
            order.verify(writer).load(ROW_ID);
            order.verify(apiClient).revokeUserToken(TOKEN);
            order.verify(writer).discard(ROW_ID);
        }

        @Test
        @DisplayName("호출이 실패하면 실패만 기록하고 다음 회차로 미룬다")
        void recordsFailureWithoutRemoving() {
            due(ROW_ID);
            loads(ROW_ID, tokenCipher.encrypt(TOKEN), tokenCipher.currentVersion());
            willThrow(new GithubApiException()).given(apiClient).revokeUserToken(TOKEN);

            assertThat(revoker.retryPending()).isZero();

            verify(writer).recordFailure(ROW_ID, "GithubApiException");
            verify(writer, never()).discard(ROW_ID);
        }

        @Test
        @DisplayName("복호화가 안 되면 GitHub을 부르지 않고 재시도만 멈춘다")
        void marksDeadWithoutCallingGithub() {
            due(ROW_ID);
            // 다른 키로 암호화된 암호문이라 지금 설정으로는 풀 수 없다.
            loads(ROW_ID, otherCipher.encrypt(TOKEN), otherCipher.currentVersion());

            assertThat(revoker.retryPending()).isZero();

            verify(apiClient, never()).revokeUserToken(anyString());
            verify(writer).markDead(ROW_ID, "DECRYPT_FAILED");
        }
    }
}

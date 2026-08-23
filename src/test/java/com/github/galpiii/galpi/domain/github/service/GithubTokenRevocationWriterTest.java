package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.entity.GithubRevocationType;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import com.github.galpiii.galpi.domain.github.exception.GithubApiException;
import com.github.galpiii.galpi.domain.github.repository.GithubTokenRevocationRepository;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenEncryptionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GithubTokenRevocationWriter — 한 건의 처리")
class GithubTokenRevocationWriterTest {

    private static final long USER_ID = 7L;
    private static final long ROW_ID = 42L;
    private static final String TOKEN = "ghu_abcdefghijklmnopqrstuvwxyz012345";

    private static final String KEY_V1 = randomKey();
    private static final String KEY_V2 = randomKey();

    @Mock
    private GithubApiClient apiClient;
    @Mock
    private GithubTokenRevocationRepository revocationRepository;
    @Mock
    private UserRepository userRepository;

    private TokenCipher tokenCipher;
    private GithubTokenRevocationWriter writer;

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static TokenCipher cipher(int currentVersion, Map<Integer, String> keys) {
        return new TokenCipher(new TokenEncryptionProperties(currentVersion, keys));
    }

    private GithubTokenRevocationWriter writerWith(TokenCipher cipher) {
        return new GithubTokenRevocationWriter(apiClient, revocationRepository, userRepository,
                cipher);
    }

    private GithubTokenRevocation rowHolding(String token, TokenCipher cipher) {
        GithubTokenRevocation row = GithubTokenRevocation.pending(
                USER_ID, cipher.encrypt(token), cipher.currentVersion(),
                GithubRevocationType.TOKEN, "GithubApiException");
        given(revocationRepository.findById(ROW_ID)).willReturn(Optional.of(row));
        return row;
    }

    private GithubTokenRevocation grantRowHolding(String token, TokenCipher cipher) {
        GithubTokenRevocation row = GithubTokenRevocation.pending(
                USER_ID, cipher.encrypt(token), cipher.currentVersion(),
                GithubRevocationType.GRANT, "GithubApiException");
        given(revocationRepository.findById(ROW_ID)).willReturn(Optional.of(row));
        return row;
    }

    private void userIsConnected(boolean connected) {
        User user = User.ofGithub(999L, "wb", "wb", null, "https://avatar");
        if (!connected) {
            user.disconnectGithub();
        }
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
    }

    @BeforeEach
    void setUp() {
        tokenCipher = cipher(1, Map.of(1, KEY_V1));
        writer = writerWith(tokenCipher);
    }

    @Test
    @DisplayName("성공하면 원래 토큰으로 폐기하고 큐에서 지운다")
    void revokesWithOriginalTokenAndClears() {
        GithubTokenRevocation row = rowHolding(TOKEN, tokenCipher);

        assertThat(writer.revokeOne(ROW_ID)).isTrue();

        verify(apiClient).revokeUserToken(TOKEN);
        verify(revocationRepository).delete(row);
    }

    @Test
    @DisplayName("또 실패하면 시도 횟수를 올리고 큐에 남겨 둔다")
    void keepsRowAndCountsAttemptOnFailure() {
        GithubTokenRevocation row = rowHolding(TOKEN, tokenCipher);
        willThrow(new GithubApiException()).given(apiClient).revokeUserToken(anyString());

        assertThat(writer.revokeOne(ROW_ID)).isFalse();

        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.isDead()).isFalse();
        verify(revocationRepository, never()).delete(row);
    }

    @Test
    @DisplayName("실패할수록 다음 시도를 뒤로 민다")
    void backsOffProgressively() {
        GithubTokenRevocation row = rowHolding(TOKEN, tokenCipher);
        willThrow(new GithubApiException()).given(apiClient).revokeUserToken(anyString());

        writer.revokeOne(ROW_ID);
        OffsetDateTime afterFirst = row.getNextAttemptAt();
        writer.revokeOne(ROW_ID);

        assertThat(row.getNextAttemptAt()).isAfter(afterFirst);
    }

    @Test
    @DisplayName("상한까지 실패하면 재시도를 멈추되 행과 암호문은 남긴다")
    void stopsRetryingButKeepsTheRow() {
        GithubTokenRevocation row = rowHolding(TOKEN, tokenCipher);
        willThrow(new GithubApiException()).given(apiClient).revokeUserToken(anyString());

        for (int i = 0; i < GithubTokenRevocation.MAX_ATTEMPTS; i++) {
            writer.revokeOne(ROW_ID);
        }

        assertThat(row.isDead()).isTrue();
        assertThat(row.getEncryptedAccessToken()).isNotBlank();
        verify(revocationRepository, never()).delete(row);
    }

    @Test
    @DisplayName("키 설정이 빠져 복호화가 안 돼도 행을 지우지 않는다 — 설정을 고치면 되살릴 수 있어야 한다")
    void keepsRowWhenKeyIsMissing() {
        // v2로 암호화해 둔 뒤 v1만 아는 상태로 읽는다 — 키 설정 누락과 같은 상황이다.
        GithubTokenRevocation row = rowHolding(TOKEN, cipher(2, Map.of(1, KEY_V1, 2, KEY_V2)));

        assertThat(writerWith(cipher(1, Map.of(1, KEY_V1))).revokeOne(ROW_ID)).isFalse();

        verify(revocationRepository, never()).delete(row);
        assertThat(row.isDead()).isTrue();
        assertThat(row.getLastError()).isEqualTo("DECRYPT_FAILED");
        assertThat(row.getEncryptedAccessToken()).isNotBlank();
    }

    @Test
    @DisplayName("복호화가 안 되면 GitHub을 부르지 않는다")
    void doesNotCallGithubWithoutAToken() {
        rowHolding(TOKEN, cipher(2, Map.of(1, KEY_V1, 2, KEY_V2)));

        writerWith(cipher(1, Map.of(1, KEY_V1))).revokeOne(ROW_ID);

        verify(apiClient, never()).revokeUserToken(anyString());
    }

    @Test
    @DisplayName("다시 연결된 사용자의 밀린 grant 폐기는 실행하지 않고 버린다 — 새 authorization이 함께 죽는다")
    void discardsStaleGrantForReconnectedUser() {
        GithubTokenRevocation row = grantRowHolding(TOKEN, tokenCipher);
        userIsConnected(true);

        assertThat(writer.revokeOne(ROW_ID)).isFalse();

        verify(apiClient, never()).revokeUserGrant(anyString());
        verify(revocationRepository).delete(row);
    }

    @Test
    @DisplayName("아직 끊긴 사용자면 밀린 grant 폐기를 그대로 실행한다")
    void stillRevokesGrantWhileDisconnected() {
        GithubTokenRevocation row = grantRowHolding(TOKEN, tokenCipher);
        userIsConnected(false);

        assertThat(writer.revokeOne(ROW_ID)).isTrue();

        verify(apiClient).revokeUserGrant(TOKEN);
        verify(revocationRepository).delete(row);
    }

    @Test
    @DisplayName("밀려난 토큰 폐기는 연결 여부와 무관하게 실행한다 — 연결된 채로 이전 토큰을 지우는 것이 정상이다")
    void revokesSupersededTokenEvenWhileConnected() {
        rowHolding(TOKEN, tokenCipher);
        userIsConnected(true);

        assertThat(writer.revokeOne(ROW_ID)).isTrue();

        verify(apiClient).revokeUserToken(TOKEN);
    }

    @Test
    @DisplayName("행이 이미 사라졌으면 조용히 끝낸다")
    void toleratesMissingRow() {
        given(revocationRepository.findById(ROW_ID)).willReturn(Optional.empty());

        assertThat(writer.revokeOne(ROW_ID)).isFalse();

        verify(apiClient, never()).revokeUserToken(anyString());
    }
}

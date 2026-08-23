package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import com.github.galpiii.galpi.domain.github.repository.GithubTokenRevocationRepository;
import com.github.galpiii.galpi.domain.github.service.GithubTokenRevocationWriter.PendingRevocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 폐기 큐를 건드리는 짧은 트랜잭션들.
 *
 * <p>여기에 GitHub 호출은 없다. 결과 기록이 <b>행이 사라진 뒤에도 안전한지</b>가 이 클래스의
 * 핵심이다 — 인스턴스가 둘이면 같은 항목을 동시에 집을 수 있고, 먼저 끝난 쪽이 이미 행을
 * 지웠을 수 있다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GithubTokenRevocationWriter — 큐 기록")
class GithubTokenRevocationWriterTest {

    private static final long USER_ID = 7L;
    private static final long ROW_ID = 42L;

    @Mock
    private GithubTokenRevocationRepository revocationRepository;

    private GithubTokenRevocationWriter writer;

    @BeforeEach
    void setUp() {
        writer = new GithubTokenRevocationWriter(revocationRepository);
    }

    private GithubTokenRevocation row() {
        GithubTokenRevocation row = GithubTokenRevocation.pending(
                USER_ID, "ciphertext", 1, "GithubApiException");
        given(revocationRepository.findById(ROW_ID)).willReturn(Optional.of(row));
        return row;
    }

    private void rowIsGone() {
        given(revocationRepository.findById(ROW_ID)).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("폐기에 필요한 값만 떠 온다 — 엔티티를 트랜잭션 밖으로 들고 나가지 않는다")
    void loadsPlainValues() {
        row();

        PendingRevocation loaded = writer.load(ROW_ID).orElseThrow();

        assertThat(loaded.userId()).isEqualTo(USER_ID);
        assertThat(loaded.encryptedAccessToken()).isEqualTo("ciphertext");
        assertThat(loaded.tokenVersion()).isEqualTo(1);
        assertThat(loaded.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("성공을 기록하면 큐에서 지운다")
    void successRemovesTheRow() {
        GithubTokenRevocation row = row();

        writer.recordSuccess(ROW_ID);

        verify(revocationRepository).delete(row);
    }

    @Test
    @DisplayName("실패하면 시도 횟수를 올리고 다음 시도를 뒤로 민다")
    void failureCountsAndBacksOff() {
        GithubTokenRevocation row = row();
        OffsetDateTime before = row.getNextAttemptAt();

        writer.recordFailure(ROW_ID, "GithubApiException");

        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getNextAttemptAt()).isAfter(before);
        verify(revocationRepository, never()).delete(row);
    }

    @Test
    @DisplayName("상한까지 실패하면 재시도를 멈추되 행과 암호문은 남긴다")
    void stopsRetryingButKeepsTheRow() {
        GithubTokenRevocation row = row();

        for (int i = 1; i < GithubTokenRevocation.MAX_ATTEMPTS; i++) {
            writer.recordFailure(ROW_ID, "GithubApiException");
        }

        assertThat(row.isDead()).isTrue();
        assertThat(row.getEncryptedAccessToken()).isNotBlank();
        verify(revocationRepository, never()).delete(row);
    }

    @Test
    @DisplayName("복호화 실패는 재시도를 멈추되 암호문을 지우지 않는다 — 설정을 고치면 되살릴 수 있어야 한다")
    void markDeadKeepsCiphertext() {
        GithubTokenRevocation row = row();

        writer.markDead(ROW_ID, "DECRYPT_FAILED");

        assertThat(row.isDead()).isTrue();
        assertThat(row.getLastError()).isEqualTo("DECRYPT_FAILED");
        assertThat(row.getEncryptedAccessToken()).isNotBlank();
        verify(revocationRepository, never()).delete(row);
    }

    @Test
    @DisplayName("행이 이미 사라졌으면 어떤 기록도 터뜨리지 않는다 — 다른 인스턴스가 먼저 끝냈을 뿐이다")
    void recordingIsSafeAfterAnotherInstanceFinished() {
        rowIsGone();

        writer.recordSuccess(ROW_ID);
        writer.recordFailure(ROW_ID, "GithubApiException");
        writer.markDead(ROW_ID, "DECRYPT_FAILED");

        assertThat(writer.load(ROW_ID)).isEmpty();
    }
}

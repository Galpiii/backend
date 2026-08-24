package com.github.galpiii.galpi.domain.github.entity;

import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * GitHub에 폐기를 요청하지 못한 user access token.
 *
 * <p>연결 해제는 GitHub 장애로 막히면 안 되지만, 그렇다고 로컬 원본만 지우고 끝내면 외부에
 * 살아 있는 토큰을 다시는 회수할 수 없다. 그래서 원본을 지울 때 암호문을 여기에 함께 남기고,
 * 폐기가 끝나지 않은 채로 남은 것을 배치가 가져간다.
 *
 * <p>들어오는 경로는 둘 다 <b>아직 폐기를 시도해 보지 않은</b> 토큰이다. 연결 해제가 로컬
 * 원본을 지우면서 함께 남기는 의도({@link #intent})와, 재로그인으로 밀려난 이전
 * 토큰({@link #superseded})이다. 그래서 새로 만들어진 행의 {@code attempts}는 언제나 0이다 —
 * 실패한 적이 없으므로 폐기율 같은 지표에서 실패로 세면 안 된다.
 *
 * <p><b>이 큐가 하는 폐기는 언제나 토큰 하나짜리다</b>({@code DELETE /applications/{id}/token}).
 * authorization 전체 폐기({@code .../grant})는 이 사용자의 <b>모든</b> 토큰을 죽이므로, 큐에
 * 남았다가 나중에 실행되면 그사이 다시 연결한 authorization까지 함께 폐기한다. 확인 시점과
 * 호출 시점이 벌어지는 한 그 경합은 검사로 막을 수 없어서, 아예 표현할 수 없게 했다 — grant
 * 폐기는 해제 요청을 처리하는 그 순간 한 번만 시도하고, 실패하면 사용자에게 GitHub 설정에서
 * 직접 해제하도록 안내한다.
 *
 * <p>재시도를 포기하더라도 행은 지우지 않는다. 재시도 중단과 회수 포기는 다른 결정이고,
 * 암호문을 잃으면 후자가 강제된다. 키 설정 누락처럼 나중에 복구되는 원인도 있으므로
 * {@link GithubTokenRevocationStatus#DEAD}로 표시만 하고 데이터는 남긴다.
 */
@Entity
@Getter
@Table(name = "github_token_revocations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GithubTokenRevocation extends BaseEntity {

    /** 이 횟수를 넘기면 자동 재시도를 멈춘다. */
    public static final int MAX_ATTEMPTS = 12;

    private static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(6);

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "text")
    private String encryptedAccessToken;

    @Column(nullable = false)
    private int tokenVersion;

    @Column(nullable = false)
    private int attempts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GithubTokenRevocationStatus status;

    @Column(nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(length = 100)
    private String lastError;

    private GithubTokenRevocation(Long userId, String encryptedAccessToken, int tokenVersion,
                                  int attempts, OffsetDateTime nextAttemptAt, String lastError) {
        this.userId = userId;
        this.encryptedAccessToken = encryptedAccessToken;
        this.tokenVersion = tokenVersion;
        this.attempts = attempts;
        this.status = GithubTokenRevocationStatus.PENDING;
        this.lastError = lastError;
        this.nextAttemptAt = nextAttemptAt;
    }

    /**
     * 연결 해제가 로컬 원본을 지우면서 함께 남기는 폐기 의도다.
     *
     * <p>원본을 지우고 나면 평문은 이 요청의 메모리에만 남는다. 그 뒤에 프로세스가 죽으면 외부에
     * 살아 있는 토큰을 회수할 수단이 사라지므로, <b>지우는 그 트랜잭션에서</b> 암호문을 함께
     * 남긴다. 커밋 뒤에 grant 폐기가 성공하면 호출자가 이 행을 지운다.
     *
     * <p>다음 회차로 바로 내보내지는 않는다. 커밋 직후의 grant 폐기 시도와 겹치면 배치가 곧
     * 죽을 토큰을 한 번 더 부르게 된다. 그 호출이 해로운 것은 아니지만 굳이 할 이유도 없다.
     */
    public static GithubTokenRevocation intent(Long userId, String encryptedAccessToken,
                                               int tokenVersion) {
        return new GithubTokenRevocation(userId, encryptedAccessToken, tokenVersion,
                0, OffsetDateTime.now().plus(FIRST_BACKOFF), null);
    }

    /**
     * 재로그인으로 새 토큰이 발급돼 밀려난 이전 토큰이다. 아직 폐기를 시도한 적이 없으니
     * {@code attempts}는 0이고, 미룰 이유도 없으니 다음 배치 회차에 바로 나간다.
     *
     * <p>로그인 응답을 기다리게 하지 않으려고 폐기 호출을 배치로 넘긴다. 로그인은 사용자가
     * 기다리는 경로이고, 이전 토큰 폐기가 몇 분 늦는 것은 감수할 수 있다.
     */
    public static GithubTokenRevocation superseded(Long userId, String encryptedAccessToken,
                                                   int tokenVersion) {
        return new GithubTokenRevocation(userId, encryptedAccessToken, tokenVersion,
                0, OffsetDateTime.now(), null);
    }

    /**
     * 재시도가 또 실패했다. 다음 시도를 지수적으로 미루고, 상한에 닿으면 재시도를 멈춘다.
     */
    public void recordFailure(String cause) {
        this.attempts++;
        this.lastError = cause;
        this.nextAttemptAt = OffsetDateTime.now().plus(backoff());
        if (this.attempts >= MAX_ATTEMPTS) {
            this.status = GithubTokenRevocationStatus.DEAD;
        }
    }

    /**
     * 재시도해 봐야 소용없는 상태임을 기록한다. 암호문은 그대로 둔다 — 원인이 사라지면
     * 운영이 상태를 되돌려 다시 시도할 수 있어야 한다.
     */
    public void markDead(String reason) {
        this.status = GithubTokenRevocationStatus.DEAD;
        this.lastError = reason;
    }

    public boolean isDead() {
        return status == GithubTokenRevocationStatus.DEAD;
    }

    private Duration backoff() {
        Duration backoff = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts, 10));
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }
}

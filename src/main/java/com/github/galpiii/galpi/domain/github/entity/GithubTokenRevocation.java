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
 * 살아 있는 토큰을 다시는 회수할 수 없다. 그래서 폐기에 실패한 토큰은 암호문 상태로 여기에
 * 남겨 두고 배치가 재시도한다.
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
                                  String lastError) {
        this.userId = userId;
        this.encryptedAccessToken = encryptedAccessToken;
        this.tokenVersion = tokenVersion;
        this.attempts = 1;
        this.status = GithubTokenRevocationStatus.PENDING;
        this.lastError = lastError;
        this.nextAttemptAt = OffsetDateTime.now().plus(FIRST_BACKOFF);
    }

    public static GithubTokenRevocation pending(Long userId, String encryptedAccessToken,
                                                int tokenVersion, String lastError) {
        return new GithubTokenRevocation(userId, encryptedAccessToken, tokenVersion, lastError);
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

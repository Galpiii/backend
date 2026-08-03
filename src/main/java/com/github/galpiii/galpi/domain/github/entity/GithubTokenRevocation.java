package com.github.galpiii.galpi.domain.github.entity;

import com.github.galpiii.galpi.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 */
@Entity
@Getter
@Table(name = "github_token_revocations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GithubTokenRevocation extends BaseEntity {

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
        this.lastError = lastError;
        this.nextAttemptAt = OffsetDateTime.now().plus(FIRST_BACKOFF);
    }

    public static GithubTokenRevocation pending(Long userId, String encryptedAccessToken,
                                                int tokenVersion, String lastError) {
        return new GithubTokenRevocation(userId, encryptedAccessToken, tokenVersion, lastError);
    }

    /**
     * 재시도가 또 실패했다. 다음 시도를 지수적으로 미룬다.
     */
    public void recordFailure(String cause) {
        this.attempts++;
        this.lastError = cause;
        this.nextAttemptAt = OffsetDateTime.now().plus(backoff());
    }

    private Duration backoff() {
        Duration backoff = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts, 10));
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }
}

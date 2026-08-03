package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import com.github.galpiii.galpi.domain.github.repository.GithubTokenRevocationRepository;
import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenCipherException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GitHub user access token 폐기를 책임진다.
 *
 * <p>폐기가 실패해도 사용자의 연결 해제는 진행돼야 하지만, 로컬 원본을 그냥 버리면 외부에
 * 살아 있는 토큰을 회수할 방법이 없어진다. 그래서 실패하면 암호문을 재시도 큐에 남기고,
 * 성공할 때까지 배치가 되돌아온다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubTokenRevoker {

    /** 이 횟수를 넘기면 자동 재시도를 멈춘다. 행은 남겨 두어 운영이 볼 수 있게 한다. */
    static final int MAX_ATTEMPTS = 12;

    private static final Limit BATCH_SIZE = Limit.of(50);

    private final GithubApiClient apiClient;
    private final GithubTokenRevocationRepository revocationRepository;
    private final TokenCipher tokenCipher;

    /**
     * 폐기를 시도하고, 실패하면 재시도 큐에 넣는다.
     *
     * <p>큐 적재까지 실패하면 예외가 그대로 올라간다. 회수할 수 없게 된 토큰을 두고
     * 연결 해제를 성공으로 보고할 수는 없다.
     */
    @Transactional
    public void revokeOrEnqueue(Long userId, String accessToken) {
        try {
            apiClient.revokeUserToken(accessToken);
            log.info("[GitHub] user token 폐기 완료 userId={}", userId);
        } catch (RuntimeException e) {
            String cause = e.getClass().getSimpleName();
            log.warn("[GitHub] user token 폐기 실패. 재시도 큐에 넣는다 userId={} cause={}", userId, cause);
            revocationRepository.save(GithubTokenRevocation.pending(
                    userId, tokenCipher.encrypt(accessToken), tokenCipher.currentVersion(), cause));
        }
    }

    /**
     * 밀린 폐기를 재시도한다.
     *
     * @return 이번 회차에 폐기에 성공한 건수
     */
    @Transactional
    public int retryPending() {
        List<GithubTokenRevocation> due = revocationRepository
                .findByAttemptsLessThanAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        MAX_ATTEMPTS, OffsetDateTime.now(), BATCH_SIZE);

        int revoked = 0;
        for (GithubTokenRevocation pending : due) {
            if (retryOne(pending)) {
                revoked++;
            }
        }
        if (!due.isEmpty()) {
            log.info("[GitHub] 밀린 토큰 폐기 재시도 대상={} 성공={}", due.size(), revoked);
        }
        return revoked;
    }

    private boolean retryOne(GithubTokenRevocation pending) {
        String accessToken;
        try {
            accessToken = tokenCipher.decrypt(pending.getEncryptedAccessToken(), pending.getTokenVersion());
        } catch (TokenCipherException e) {
            log.error("[GitHub] 폐기 대기 토큰을 복호화할 수 없어 폐기를 포기한다 userId={} tokenVersion={}",
                    pending.getUserId(), pending.getTokenVersion());
            revocationRepository.delete(pending);
            return false;
        }

        try {
            apiClient.revokeUserToken(accessToken);
            revocationRepository.delete(pending);
            log.info("[GitHub] 밀린 user token 폐기 성공 userId={} 시도={}",
                    pending.getUserId(), pending.getAttempts());
            return true;
        } catch (RuntimeException e) {
            pending.recordFailure(e.getClass().getSimpleName());
            if (pending.getAttempts() >= MAX_ATTEMPTS) {
                log.error("[GitHub] user token 폐기를 {}회 실패해 자동 재시도를 멈춘다. "
                                + "github_token_revocations 행을 직접 확인하세요 userId={}",
                        MAX_ATTEMPTS, pending.getUserId());
            }
            return false;
        }
    }
}

package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocationStatus;
import com.github.galpiii.galpi.domain.github.repository.GithubTokenRevocationRepository;
import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenCipherException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 폐기 큐의 행 하나를 자기 트랜잭션 안에서 처리한다.
 *
 * <p>배치 전체를 한 트랜잭션으로 묶으면 마지막 행의 실패가 앞서 성공한 폐기의 삭제와
 * 시도 횟수 기록까지 되돌린다. 그래서 행 단위로 커밋한다. 프록시를 거쳐야 트랜잭션이
 * 걸리므로 호출자와 다른 빈으로 분리했다(GithubUserService/GithubUserWriter와 같은 이유).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GithubTokenRevocationWriter {

    private final GithubApiClient apiClient;
    private final GithubTokenRevocationRepository revocationRepository;
    private final TokenCipher tokenCipher;

    @Transactional(readOnly = true)
    List<Long> findDueIds(Limit limit) {
        return revocationRepository.findDueIds(
                GithubTokenRevocationStatus.PENDING, OffsetDateTime.now(), limit);
    }

    /**
     * 한 건을 폐기한다.
     *
     * <p>GitHub 호출이 트랜잭션 안에서 일어나 그동안 커넥션을 쥔다. 배치가 5분에 한 번,
     * 최대 50건을 순차 처리하므로 한 번에 한 커넥션이고 풀(기본 10)에 여유가 있다.
     * 배치가 커지면 호출을 트랜잭션 밖으로 빼야 한다.
     *
     * @return 이번에 폐기에 성공했으면 true
     */
    @Transactional
    boolean revokeOne(Long id) {
        GithubTokenRevocation pending = revocationRepository.findById(id).orElse(null);
        if (pending == null) {
            return false;
        }

        String accessToken;
        try {
            accessToken = tokenCipher.decrypt(pending.getEncryptedAccessToken(), pending.getTokenVersion());
        } catch (TokenCipherException e) {
            // 키 설정 누락처럼 복구 가능한 원인일 수 있다. 암호문은 절대 지우지 않는다.
            pending.markDead("DECRYPT_FAILED");
            log.error("[GitHub] 폐기 대기 토큰을 복호화할 수 없어 자동 재시도를 멈춘다. "
                            + "키 설정을 확인한 뒤 status를 PENDING으로 되돌리면 재개된다 "
                            + "userId={} tokenVersion={}",
                    pending.getUserId(), pending.getTokenVersion());
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
            if (pending.isDead()) {
                log.error("[GitHub] user token 폐기를 {}회 실패해 자동 재시도를 멈춘다. "
                                + "github_token_revocations 행이 남아 있으니 직접 확인하세요 userId={}",
                        GithubTokenRevocation.MAX_ATTEMPTS, pending.getUserId());
            }
            return false;
        }
    }
}

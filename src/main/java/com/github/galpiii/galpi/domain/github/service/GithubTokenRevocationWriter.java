package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.entity.GithubRevocationType;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocationStatus;
import com.github.galpiii.galpi.domain.github.repository.GithubTokenRevocationRepository;
import com.github.galpiii.galpi.domain.user.entity.GithubConnectionStatus;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenCipherException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
class GithubTokenRevocationWriter {

    private final GithubApiClient apiClient;
    private final GithubTokenRevocationRepository revocationRepository;
    private final UserRepository userRepository;
    private final TokenCipher tokenCipher;

    /** 폐기에 실패한 암호문을 재시도 큐에 남긴다. 외부 호출과 분리된 짧은 트랜잭션이다. */
    @Transactional
    void enqueueFailed(Long userId, String encryptedAccessToken, int tokenVersion,
                       GithubRevocationType type, String cause) {
        revocationRepository.save(GithubTokenRevocation.pending(
                userId, encryptedAccessToken, tokenVersion, type, cause));
    }

    @Transactional(readOnly = true)
    List<Long> findDueIds(Limit limit) {
        return revocationRepository.findDueIds(
                GithubTokenRevocationStatus.PENDING, OffsetDateTime.now(), limit);
    }

    @Transactional
    boolean revokeOne(Long id) {
        GithubTokenRevocation pending = revocationRepository.findById(id).orElse(null);
        if (pending == null) {
            return false;
        }

        // 재연결 경로가 큐를 비우지만, 적재와 재연결이 겹치면 항목이 남을 수 있다. grant 폐기는
        // authorization 전체를 지우므로 호출 직전에 한 번 더 확인한다 — 다시 연결된 사용자라면
        // 그 의도는 이미 소멸했다. 밀려난 토큰 하나를 지우는 TOKEN에는 이 조건을 걸지 않는다.
        // 연결된 상태에서 이전 토큰을 폐기하는 것이 그쪽의 정상 동작이다.
        if (pending.getRevocationType() == GithubRevocationType.GRANT && isConnected(pending)) {
            revocationRepository.delete(pending);
            log.info("[GitHub] 다시 연결된 사용자라 밀린 grant 폐기를 버린다 userId={}",
                    pending.getUserId());
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
            // 적재 시점에 정해진 종류를 그대로 따른다. 여기서 종류를 하나로 뭉뚱그리면
            // 밀려난 토큰 하나를 지우려다 사용자의 authorization 전체를 폐기하게 된다.
            if (pending.getRevocationType() == GithubRevocationType.GRANT) {
                apiClient.revokeUserGrant(accessToken);
            } else {
                apiClient.revokeUserToken(accessToken);
            }
            revocationRepository.delete(pending);
            log.info("[GitHub] 밀린 {} 폐기 성공 userId={} 시도={}",
                    pending.getRevocationType(), pending.getUserId(), pending.getAttempts());
            return true;
        } catch (RuntimeException e) {
            pending.recordFailure(e.getClass().getSimpleName());
            if (pending.isDead()) {
                log.error("[GitHub] 토큰 폐기를 {}회 실패해 자동 재시도를 멈춘다. "
                                + "github_token_revocations 행이 남아 있으니 직접 확인하세요 userId={}",
                        GithubTokenRevocation.MAX_ATTEMPTS, pending.getUserId());
            }
            return false;
        }
    }

    private boolean isConnected(GithubTokenRevocation pending) {
        return userRepository.findById(pending.getUserId())
                .map(User::getGithubConnectionStatus)
                .filter(GithubConnectionStatus.CONNECTED::equals)
                .isPresent();
    }
}

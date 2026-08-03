package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import com.github.galpiii.galpi.domain.github.repository.GithubTokenRevocationRepository;
import com.github.galpiii.galpi.global.crypto.TokenCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final Limit BATCH_SIZE = Limit.of(50);

    private final GithubApiClient apiClient;
    private final GithubTokenRevocationRepository revocationRepository;
    private final GithubTokenRevocationWriter writer;
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
     * 밀린 폐기를 재시도한다. 각 건은 독립된 트랜잭션이라 한 건의 실패가 나머지를 되돌리지 않는다.
     *
     * @return 이번 회차에 폐기에 성공한 건수
     */
    public int retryPending() {
        List<Long> dueIds = writer.findDueIds(BATCH_SIZE);
        if (dueIds.isEmpty()) {
            return 0;
        }

        int revoked = 0;
        for (Long id : dueIds) {
            try {
                if (writer.revokeOne(id)) {
                    revoked++;
                }
            } catch (RuntimeException e) {
                // 커밋 실패처럼 revokeOne 안에서 못 삼킨 것들. 이 건만 다음 회차로 미룬다.
                log.warn("[GitHub] 폐기 재시도 중 처리하지 못한 항목을 건너뛴다 id={} cause={}",
                        id, e.getClass().getSimpleName());
            }
        }

        log.info("[GitHub] 밀린 토큰 폐기 재시도 대상={} 성공={}", dueIds.size(), revoked);
        return revoked;
    }
}

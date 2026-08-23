package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.entity.GithubRevocationType;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import com.github.galpiii.galpi.domain.github.repository.GithubTokenRevocationRepository;
import com.github.galpiii.galpi.global.crypto.TokenCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

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
     * <p>GitHub 호출은 트랜잭션 밖에서 한다. 큐 적재는 실패 경로에서만 필요하므로, 성공하는
     * 대다수 호출까지 트랜잭션 경계 안에 둘 이유가 없다.
     *
     * <p>큐 적재까지 실패하면 예외가 그대로 올라간다. 회수할 수 없게 된 토큰을 두고
     * 연결 해제를 성공으로 보고할 수는 없다.
     *
     * @return GitHub이 폐기를 받아들였으면 true. false면 큐에 밀려 있는 상태이므로 호출자는
     *         사용자에게 GitHub 설정에서 직접 해제하는 방법을 함께 안내해야 한다
     */
    public boolean revokeOrEnqueue(Long userId, String accessToken, GithubRevocationType type) {
        try {
            call(type, accessToken);
            log.info("[GitHub] {} 폐기 완료 userId={}", type, userId);
            return true;
        } catch (RuntimeException e) {
            String cause = e.getClass().getSimpleName();
            log.warn("[GitHub] {} 폐기 실패. 재시도 큐에 넣는다 userId={} cause={}", type, userId, cause);
            writer.enqueueFailed(userId, tokenCipher.encrypt(accessToken),
                    tokenCipher.currentVersion(), type, cause);
            return false;
        }
    }

    private void call(GithubRevocationType type, String accessToken) {
        if (type == GithubRevocationType.GRANT) {
            apiClient.revokeUserGrant(accessToken);
            return;
        }
        apiClient.revokeUserToken(accessToken);
    }

    /**
     * 재로그인으로 밀려난 이전 토큰을 폐기 큐에 넣기만 한다.
     *
     * <p>여기서 GitHub을 부르지 않는 이유는 호출자가 로그인 경로이기 때문이다. 폐기 호출은
     * 로그인 성공과 아무 상관이 없는데, 여기에 붙이면 GitHub이 느린 만큼 로그인이 느려지고
     * 실패 경로도 하나 는다. 배치가 최대 5분 뒤에 가져간다.
     *
     * <p>이미 암호문을 받으므로 복호화 없이 그대로 옮긴다. 호출자의 트랜잭션에 참여해
     * 원본 교체와 함께 커밋된다 — 이 순서가 깨지면 회수할 수 없는 토큰이 생긴다.
     */
    public void enqueueSuperseded(Long userId, String encryptedAccessToken, int tokenVersion) {
        revocationRepository.save(
                GithubTokenRevocation.superseded(userId, encryptedAccessToken, tokenVersion));
        log.info("[GitHub] 재로그인으로 밀려난 이전 토큰을 폐기 큐에 넣는다 userId={}", userId);
    }

    /**
     * 밀려 있던 grant 폐기 의도를 버린다. 새 authorization을 받는 경로가 부른다.
     *
     * <p>grant 폐기는 이 사용자의 App authorization <b>전체</b>를 지운다. 해제 때 폐기가
     * 실패해 큐에 남은 항목을 그대로 두면, 사용자가 다시 연결한 뒤 배치가 옛 토큰으로 그
     * 항목을 처리하면서 방금 승인한 authorization까지 함께 폐기한다. 재승인을 받은 순간
     * 그 의도는 소멸했으므로 여기서 지운다.
     *
     * <p>호출자의 트랜잭션에 참여해 새 토큰 저장과 함께 커밋된다. 새 토큰만 커밋되고 이
     * 삭제가 빠지면 막으려던 상황이 그대로 남는다.
     */
    public void discardPendingGrants(Long userId) {
        int discarded = revocationRepository.deleteByUserIdAndRevocationType(
                userId, GithubRevocationType.GRANT);
        if (discarded > 0) {
            log.info("[GitHub] 재연결로 의미를 잃은 grant 폐기 대기를 버린다 userId={} count={}",
                    userId, discarded);
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

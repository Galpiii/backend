package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import com.github.galpiii.galpi.domain.github.repository.GithubTokenRevocationRepository;
import com.github.galpiii.galpi.domain.github.service.GithubTokenRevocationWriter.PendingRevocation;
import com.github.galpiii.galpi.global.crypto.TokenCipher;
import com.github.galpiii.galpi.global.crypto.TokenCipherException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * GitHub user access token 폐기를 책임진다.
 *
 * <p>폐기가 실패해도 사용자의 연결 해제는 진행돼야 하지만, 로컬 원본을 그냥 버리면 외부에
 * 살아 있는 토큰을 회수할 방법이 없어진다. 그래서 원본을 지우는 트랜잭션에서 암호문을 재시도
 * 큐에 함께 남기고, 폐기가 끝나지 않은 채 남은 것을 배치가 가져간다.
 *
 * <p>다만 <b>큐에 들어가는 것은 언제나 토큰 하나짜리 폐기</b>다. authorization 전체 폐기는
 * 시도할 그 순간에만 의미가 있다 — 미뤄 두면 그사이 사용자가 다시 연결했을 때 방금 승인한
 * authorization을 죽인다. 이 클래스에 grant를 큐에 넣는 경로가 없는 것은 그래서다.
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
     * 연결 해제를 커밋하기 전에 토큰 폐기 의도를 큐에 남긴다.
     *
     * <p><b>호출자의 트랜잭션에 참여한다.</b> 원본을 지우는 그 트랜잭션과 함께 커밋돼야 의미가
     * 있다 — 따로 커밋하면 "지웠는데 회수할 암호문이 없는" 또는 "지우지도 않았는데 폐기 예약만
     * 남은" 상태가 생긴다. 뒤쪽은 아직 연결된 사용자의 토큰을 배치가 죽이는 쪽이라 특히 나쁘다.
     *
     * <p>여기서 GitHub을 부르지 않는 것은 응답을 기다리는 동안 트랜잭션과 DB 커넥션을 잡고
     * 있지 않기 위해서다. 폐기 시도는 커밋 뒤에
     * {@link #revokeGrantAfterDisconnect(Long, String, Long)}가 이어받는다.
     *
     * @return 커밋 뒤 grant 폐기에 성공하면 지울 큐 행의 id
     */
    public Long enqueueDisconnectIntent(Long userId, String accessToken) {
        GithubTokenRevocation queued = revocationRepository.save(GithubTokenRevocation.intent(
                userId, tokenCipher.encrypt(accessToken), tokenCipher.currentVersion()));
        return queued.getId();
    }

    /**
     * 커밋된 해제에 이어 authorization 전체 폐기를 <b>한 번만</b> 시도한다.
     *
     * <p>재시도 종류가 바뀌는 것이 이 메서드의 요점이다. grant 폐기를 그대로 미뤄 두면 나중에
     * 실행될 때 그사이 다시 연결한 authorization까지 죽인다. 반면 큐에 남아 있는 것은 토큰
     * 하나짜리 폐기라 언제 실행돼도 안전하고, 회수해야 할 자격증명은 어차피 그 토큰이다.
     *
     * <p>그래서 실패해도 여기서 할 일이 없다 — 이미 커밋된 큐 행이 폴백이다. 그 대신
     * authorization 자체는 GitHub에 남으므로, 호출자는 폐기됐다고 답해서는 안 되고 사용자에게
     * GitHub 설정에서 직접 해제하는 방법을 안내해야 한다.
     *
     * @param revocationId {@link #enqueueDisconnectIntent(Long, String)}가 남긴 큐 행
     * @return authorization 폐기를 GitHub이 받아들였으면 true
     */
    public boolean revokeGrantAfterDisconnect(Long userId, String accessToken, Long revocationId) {
        try {
            apiClient.revokeUserGrant(accessToken);
        } catch (RuntimeException e) {
            log.warn("[GitHub] authorization 폐기 실패. 큐에 남은 토큰 폐기로 이어진다 "
                    + "userId={} cause={}", userId, e.getClass().getSimpleName());
            return false;
        }

        log.info("[GitHub] authorization 폐기 완료 userId={}", userId);
        discardQueuedIntent(userId, revocationId);
        return true;
    }

    /**
     * grant 폐기가 성공했으면 큐에 남은 토큰 폐기는 할 일이 없다.
     *
     * <p>지우지 못해도 손해는 없다 — 이미 죽은 토큰이라 배치가 한 번 더 불러 봐야 404로 성공
     * 처리되고 그때 지워진다. 여기서 예외를 올리면 이미 끝난 해제를 실패로 보고하게 되므로
     * 삼킨다.
     */
    private void discardQueuedIntent(Long userId, Long revocationId) {
        try {
            writer.discard(revocationId);
        } catch (RuntimeException e) {
            log.warn("[GitHub] 폐기 의도 행을 지우지 못했다. 배치가 죽은 토큰을 한 번 더 부른다 "
                    + "userId={} cause={}", userId, e.getClass().getSimpleName());
        }
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
     * 밀린 폐기를 재시도한다. 각 건은 독립적이라 한 건의 실패가 나머지를 되돌리지 않는다.
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
                if (revokeOne(id)) {
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

    /**
     * 한 건을 처리한다. 읽기·HTTP·기록이 서로 다른 구간이다.
     *
     * <p>GitHub 호출을 트랜잭션 안에 두면 응답을 기다리는 내내 DB 커넥션이 묶인다. 읽기와
     * 기록만 짧게 트랜잭션을 잡고, 그 사이 호출은 아무것도 붙들지 않은 채 한다.
     */
    private boolean revokeOne(Long id) {
        PendingRevocation pending = writer.load(id).orElse(null);
        if (pending == null) {
            return false;
        }

        String accessToken;
        try {
            accessToken = tokenCipher.decrypt(
                    pending.encryptedAccessToken(), pending.tokenVersion());
        } catch (TokenCipherException e) {
            // 키 설정 누락처럼 복구 가능한 원인일 수 있다. 암호문은 절대 지우지 않는다.
            writer.markDead(id, "DECRYPT_FAILED");
            log.error("[GitHub] 폐기 대기 토큰을 복호화할 수 없어 자동 재시도를 멈춘다. "
                            + "키 설정을 확인한 뒤 status를 PENDING으로 되돌리면 재개된다 "
                            + "userId={} tokenVersion={}",
                    pending.userId(), pending.tokenVersion());
            return false;
        }

        try {
            apiClient.revokeUserToken(accessToken);
        } catch (RuntimeException e) {
            writer.recordFailure(id, e.getClass().getSimpleName());
            return false;
        }

        writer.discard(id);
        log.info("[GitHub] 밀린 토큰 폐기 성공 userId={} 시도={}",
                pending.userId(), pending.attempts());
        return true;
    }
}

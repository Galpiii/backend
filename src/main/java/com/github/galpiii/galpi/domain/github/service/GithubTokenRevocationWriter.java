package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocation;
import com.github.galpiii.galpi.domain.github.entity.GithubTokenRevocationStatus;
import com.github.galpiii.galpi.domain.github.repository.GithubTokenRevocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 폐기 큐를 건드리는 짧은 트랜잭션들.
 *
 * <p>GitHub 호출은 여기 없다. 한 건을 처리하는 흐름은 <b>읽기 → HTTP → 결과 기록</b>으로 끊겨
 * 있고, 그 사이에는 트랜잭션도 DB 커넥션도 잡지 않는다. 하나로 묶으면 GitHub이 응답하지 않는
 * 동안 커넥션이 그대로 묶인다.
 *
 * <p>결과 기록은 행이 사라졌어도 조용히 넘어간다. 인스턴스가 둘이면 같은 항목을 동시에 집을 수
 * 있는데, 토큰 폐기는 두 번 불러도 두 번째가 404로 성공 처리되므로 손해가 없다. 남은 것은
 * "먼저 끝난 쪽이 지운 행에 결과를 쓰려는" 상황뿐이라 그것만 무시하면 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GithubTokenRevocationWriter {

    private final GithubTokenRevocationRepository revocationRepository;

    @Transactional(readOnly = true)
    List<Long> findDueIds(Limit limit) {
        return revocationRepository.findDueIds(
                GithubTokenRevocationStatus.PENDING, OffsetDateTime.now(), limit);
    }

    /** 폐기에 필요한 값만 떠 온다. 엔티티를 들고 나가면 트랜잭션 밖에서 지연 로딩에 걸린다. */
    @Transactional(readOnly = true)
    Optional<PendingRevocation> load(Long id) {
        return revocationRepository.findById(id)
                .map(row -> new PendingRevocation(row.getId(), row.getUserId(),
                        row.getEncryptedAccessToken(), row.getTokenVersion(), row.getAttempts()));
    }

    /**
     * 더 폐기할 것이 없어진 항목을 큐에서 지운다. 폐기에 성공했거나, grant 폐기가 먼저 그
     * 토큰을 죽였거나 — 어느 쪽이든 남겨 둘 이유가 없다.
     */
    @Transactional
    void discard(Long id) {
        revocationRepository.findById(id).ifPresent(revocationRepository::delete);
    }

    @Transactional
    void recordFailure(Long id, String cause) {
        revocationRepository.findById(id).ifPresent(row -> {
            row.recordFailure(cause);
            if (row.isDead()) {
                log.error("[GitHub] 토큰 폐기를 {}회 실패해 자동 재시도를 멈춘다. "
                                + "github_token_revocations 행이 남아 있으니 직접 확인하세요 userId={}",
                        GithubTokenRevocation.MAX_ATTEMPTS, row.getUserId());
            }
        });
    }

    /** 재시도해도 소용없는 상태로 표시한다. 암호문은 지우지 않는다. */
    @Transactional
    void markDead(Long id, String reason) {
        revocationRepository.findById(id).ifPresent(row -> row.markDead(reason));
    }

    /** 트랜잭션 밖으로 들고 나갈 값들. */
    record PendingRevocation(Long id, Long userId, String encryptedAccessToken, int tokenVersion,
                             int attempts) {
    }
}

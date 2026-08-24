package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.event.GithubDisconnectedEvent;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연결 해제의 로컬 정리를 한 트랜잭션으로 묶는다.
 *
 * <p>토큰 원본 삭제, 연결 상태 변경, 진행 중 분석 취소는 <b>같이 일어나거나 아예 일어나지
 * 말아야</b> 한다. 따로 커밋하면 어느 하나가 실패했을 때 반쪽 상태가 남는다 — 토큰만 지워졌는데
 * 분석은 계속 도는 쪽이든, 분석만 취소됐는데 연결은 살아 있는 쪽이든 자동으로 복구할 수단이
 * 없다.
 *
 * <p>GitHub 폐기 <b>호출</b>은 여기 들어오지 않는다. 외부 응답을 기다리는 동안 트랜잭션과 DB
 * 커넥션을 잡고 있을 이유가 없다. 대신 <b>폐기 의도</b>는 여기서 함께 커밋한다 — 원본을 지우고
 * 나면 평문은 이 요청의 메모리에만 남으므로, 그 뒤에 프로세스가 죽으면 외부에 살아 있는 토큰을
 * 회수할 수단이 사라진다. 커밋 뒤의 폐기 시도는 호출자가 이어받는다.
 *
 * <p>첫 구문은 사용자 행 잠금이다. 분석 작업 생성도 같은 잠금을 먼저 잡으므로, 해제와 생성이
 * 겹쳐 "취소된 뒤에 생성되는" 순서가 만들어지지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GithubDisconnectWriter {

    private final UserRepository userRepository;
    private final GithubUserTokenService userTokenService;
    private final GithubTokenRevoker tokenRevoker;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * @param accessToken 폐기 의도로 남길 평문 토큰. 없거나 만료됐으면 {@code null}
     * @return 남긴 폐기 의도 행의 id. 토큰이 없었으면 {@code null}
     */
    @Transactional
    Long disconnect(Long userId, String accessToken) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED));

        Long revocationId = accessToken == null
                ? null
                : tokenRevoker.enqueueDisconnectIntent(userId, accessToken);

        userTokenService.delete(userId);
        user.disconnectGithub();

        // 구독자는 이 트랜잭션에 참여한다. 취소가 실패하면 연결 해제 전체가 함께 되돌아간다.
        eventPublisher.publishEvent(new GithubDisconnectedEvent(userId));
        return revocationId;
    }
}

package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.dto.GithubDisconnectResponse;
import com.github.galpiii.galpi.domain.github.entity.GithubRevocationType;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * GitHub 연결 해제.
 *
 * <p>지우는 것은 <b>접근 수단</b>뿐이다. 연결되어 있는 동안 모은 것 — 저장소 연결, PR, 분석
 * 결과 — 은 그대로 둔다. 사용자가 끊고 싶은 것은 갈피가 GitHub에 접근하는 권한이지 과거
 * 분석 결과가 아니고, 재연결하면 {@code github_repository_id} 기준으로 그대로 이어진다.
 *
 * <p>진행 중인 분석만은 예외다. 워커는 작업 생성 시점에 고정된 installation token으로 돌아
 * user token을 지워도 멈추지 않으므로, 권한 근거가 사라진 작업을 명시적으로 취소한다.
 *
 * <p>순서가 이 클래스의 전부다. ① 외부 폐기를 트랜잭션 밖에서 시도하고, ② 실패하면 짧은
 * 트랜잭션으로 재시도 큐에 남기고, ③ 로컬 정리는 하나의 트랜잭션에서 끝낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubConnectionService {

    private final GithubUserTokenService userTokenService;
    private final GithubTokenRevoker tokenRevoker;
    private final GithubDisconnectWriter disconnectWriter;
    private final UserRepository userRepository;
    private final GithubAppProperties properties;

    /**
     * 연결을 끊는다.
     *
     * <p>유효한 토큰이 있으면 App authorization 전체를 폐기한다. 토큰이 없거나 만료됐으면
     * 폐기 API를 부를 수단이 없는데, <b>해제만을 위해 재인증을 요구하지는 않는다</b> —
     * 갈피 쪽 정리를 끝내고 GitHub 설정에서 직접 해제할 링크를 함께 돌려준다.
     *
     * <p>App 설치는 건드리지 않는다. 조직 설치는 다른 갈피 사용자·프로젝트가 공유할 수 있어
     * 한 사람의 해제로 지우면 남의 분석이 함께 멈춘다.
     */
    public GithubDisconnectResponse disconnect(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED);
        }

        // 로컬 원본을 지우기 전에 폐기를 넘긴다. 순서가 뒤집히면 복호화할 원본이 사라져
        // 외부에 살아 있는 authorization을 회수할 수단이 없어진다.
        boolean revoked = userTokenService.find(userId)
                .map(accessToken -> tokenRevoker.revokeOrEnqueue(
                        userId, accessToken, GithubRevocationType.GRANT))
                .orElse(false);

        disconnectWriter.disconnect(userId);

        log.info("[GitHub] 사용자 요청으로 연결을 해제 userId={} authorizationRevoked={}",
                userId, revoked);
        return new GithubDisconnectResponse(revoked,
                properties.userAuthorizationsUrl(), properties.userInstallationsUrl());
    }
}

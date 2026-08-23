package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.config.GithubAppProperties;
import com.github.galpiii.galpi.domain.github.dto.GithubDisconnectResponse;
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
 * <p>순서가 이 클래스의 전부다. ① 로컬 정리와 폐기 의도를 <b>하나의 트랜잭션으로 먼저</b>
 * 커밋하고, ② 그다음에 외부 폐기를 트랜잭션 밖에서 시도하고, ③ 성공하면 남겨 둔 의도를 지운다.
 * 외부 호출이 앞서면 그것만 되돌릴 수 없는 채로 로컬이 실패할 수 있다.
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
     * <p>유효한 토큰이 있으면 App authorization 전체를 폐기한다. 이 시도는 <b>여기서 한 번</b>이
     * 전부다. 실패하면 그 토큰 하나를 폐기하도록 큐에 남은 의도가 이어받고 authorization은
     * 사용자가 직접 해제하게 안내한다 — 미룬 grant 폐기는 그사이 재연결한 권한까지 죽인다.
     *
     * <p>그 의도를 <b>로컬 정리와 같은 트랜잭션에서 먼저</b> 커밋하는 것이 이 메서드의 순서다.
     * 폐기를 앞세우면 뒤따르는 로컬 정리가 실패했을 때 되돌릴 수 없는 상태가 남는다 — grant가
     * 폐기됐는데 갈피는 CONNECTED이거나, 아직 연결된 사용자의 토큰을 배치가 곧 죽이거나.
     * 반대로 이 순서에서는 로컬 정리가 실패하면 아무것도 커밋되지 않아 그대로 재시도할 수 있고,
     * 커밋 뒤에 프로세스가 죽어도 남는 것은 토큰 하나짜리 폐기라 나중에 실행돼도 안전하다.
     *
     * <p>토큰이 없거나 만료됐으면 폐기 API를 부를 수단이 없는데, <b>해제만을 위해 재인증을
     * 요구하지는 않는다</b> — 갈피 쪽 정리를 끝내고 GitHub 설정에서 직접 해제할 링크를 함께
     * 돌려준다.
     *
     * <p>보장 범위를 넘겨 말하지 않는다. 갈피가 회수할 수 있는 것은 <b>보관하던 access
     * token</b>뿐이다. 만료형 토큰을 쓰는 앱이면 refresh token이 함께 발급되는데 갈피는 그것을
     * 저장하지 않으므로 폐기할 수단도 없다. authorization 전체를 확실히 지우는 방법은 GitHub
     * 설정에서 직접 해제하는 것뿐이고, 그래서 그 링크가 응답의 곁다리가 아니다.
     *
     * <p>App 설치는 건드리지 않는다. 조직 설치는 다른 갈피 사용자·프로젝트가 공유할 수 있어
     * 한 사람의 해제로 지우면 남의 분석이 함께 멈춘다.
     */
    public GithubDisconnectResponse disconnect(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED);
        }

        // 평문은 지금 읽어 둔다. 원본을 지우고 나면 복호화할 것이 남지 않는다.
        String accessToken = userTokenService.find(userId).orElse(null);

        Long revocationId = disconnectWriter.disconnect(userId, accessToken);

        boolean revoked = accessToken != null
                && tokenRevoker.revokeGrantAfterDisconnect(userId, accessToken, revocationId);

        log.info("[GitHub] 사용자 요청으로 연결을 해제 userId={} authorizationRevoked={}",
                userId, revoked);
        return new GithubDisconnectResponse(revoked,
                properties.userAuthorizationsUrl(), properties.userInstallationsUrl());
    }
}

package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.GithubApiClient;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GithubConnectionService {

    private final GithubUserTokenService userTokenService;
    private final GithubUserWriter userWriter;
    private final GithubApiClient apiClient;
    private final UserRepository userRepository;

    /**
     * 일부러 트랜잭션을 열지 않는다. GitHub 폐기 호출이 read timeout(기본 15s)까지 걸릴 수 있어
     * 트랜잭션 안에서 부르면 그동안 커넥션을 물고 있게 된다. 상태 변경만 {@link GithubUserWriter}가
     * 짧은 트랜잭션으로 처리한다.
     */
    public void disconnect(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED);
        }

        userTokenService.find(userId).ifPresent(this::revokeAtGithub);
        userTokenService.delete(userId);
        userWriter.disconnectGithub(userId);

        log.info("[GitHub] 사용자 요청으로 연결을 해제 userId={}", userId);
    }

    /**
     * GitHub이 응답하지 않아도 로컬 연결 해제는 진행한다. 여기서 실패를 전파하면 GitHub 장애 중에
     * 사용자가 연결을 끊을 수 없게 되는데, 그게 저장된 토큰이 남는 것보다 나쁘다.
     */
    private void revokeAtGithub(String accessToken) {
        try {
            apiClient.revokeUserToken(accessToken);
        } catch (RuntimeException e) {
            log.warn("[GitHub] 토큰 폐기 요청 실패. 로컬 연결 해제는 계속 진행한다. cause={}",
                    e.getClass().getSimpleName());
        }
    }
}

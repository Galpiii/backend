package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 연결을 확정하는 하나의 트랜잭션.
 *
 * <p>프로필 갱신, 이전 토큰 폐기 큐 적재, 새 토큰 저장, 연결 상태 전이가 함께 커밋된다. 이걸
 * 쪼개면 중간 상태가 밖에서 보인다 — 특히 "CONNECTED인데 토큰이 없는" 상태와 "DISCONNECTED인데
 * 유효한 토큰이 있는" 상태가 생긴다. 뒤쪽이 위험하다. 연결 상태가 아니라 토큰만 보는 경로
 * (저장소 조회·해제 등)가 그 토큰으로 GitHub을 계속 부를 수 있기 때문이다.
 *
 * <p>첫 구문은 사용자 행 잠금이다. 연결 해제와 분석 생성도 같은 잠금을 먼저 잡으므로, 세
 * 경로가 서로 끼어들지 않고 줄을 선다 — 잠금 순서가 같아 데드락도 생기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GithubConnectWriter {

    private final UserRepository userRepository;
    private final GithubUserTokenService userTokenService;

    @Transactional
    User connect(Long userId, GithubUserResponse githubUser, String accessToken,
                 Duration expiresIn) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED));

        user.syncGithubProfile(githubUser.login(), githubUser.name(), githubUser.email(),
                githubUser.avatarUrl());
        userTokenService.save(user, accessToken, expiresIn);
        // 토큰이 자리 잡은 뒤에야 연결이다. 순서가 뒤집히면 저장 실패 시 연결만 남는다.
        user.connectGithub();

        log.info("[GitHub] 연결을 확정했다 userId={}", userId);
        return user;
    }
}

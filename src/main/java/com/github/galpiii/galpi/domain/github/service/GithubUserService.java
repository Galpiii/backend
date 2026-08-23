package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GithubUserService {

    private final GithubUserWriter writer;

    /**
     * 회원 행을 확보한다. 연결 상태와 프로필은 여기서 건드리지 않는다 — 그 둘은 토큰 저장과
     * 같은 트랜잭션에서 확정된다.
     */
    public User findOrCreate(GithubUserResponse githubUser) {
        try {
            return writer.findOrCreate(githubUser);
        } catch (DataIntegrityViolationException e) {
            log.info("[GitHub] 회원 생성 경합 감지. 기존 행을 재조회한다. githubId={}", githubUser.id());
            return writer.find(githubUser)
                    .orElseThrow(() -> {
                        log.warn("[GitHub] UNIQUE 충돌 후에도 회원을 찾지 못했다. githubId={}", githubUser.id());
                        return new UnauthorizedException(ErrorCode.LOGIN_FAILED);
                    });
        }
    }
}

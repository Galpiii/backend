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

    public User upsert(GithubUserResponse githubUser) {
        try {
            return writer.updateExistingOrCreate(githubUser);
        } catch (DataIntegrityViolationException e) {
            log.info("[GitHub] 회원 생성 경합 감지. 기존 행을 재조회한다. githubId={}", githubUser.id());
            return writer.updateExisting(githubUser)
                    .orElseThrow(() -> {
                        log.warn("[GitHub] UNIQUE 충돌 후에도 회원을 찾지 못했다. githubId={}", githubUser.id());
                        return new UnauthorizedException(ErrorCode.LOGIN_FAILED);
                    });
        }
    }
}

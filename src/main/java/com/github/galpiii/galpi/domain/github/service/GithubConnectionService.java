package com.github.galpiii.galpi.domain.github.service;

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
    private final GithubTokenRevoker tokenRevoker;
    private final UserRepository userRepository;

    public void disconnect(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED);
        }

        userTokenService.find(userId)
                .ifPresent(accessToken -> tokenRevoker.revokeOrEnqueue(userId, accessToken));
        userTokenService.delete(userId);
        userWriter.disconnectGithub(userId);

        log.info("[GitHub] 사용자 요청으로 연결을 해제 userId={}", userId);
    }
}

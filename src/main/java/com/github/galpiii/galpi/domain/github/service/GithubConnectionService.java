package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GithubConnectionService {

    private final GithubUserTokenService userTokenService;
    private final UserRepository userRepository;

    @Transactional
    public void disconnect(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED));

        userTokenService.delete(userId);
        user.disconnectGithub();
        log.info("[GitHub] 사용자 요청으로 연결을 해제 userId={}", userId);
    }
}

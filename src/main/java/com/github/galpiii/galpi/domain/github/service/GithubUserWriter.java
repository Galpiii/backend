package com.github.galpiii.galpi.domain.github.service;

import com.github.galpiii.galpi.domain.github.client.dto.GithubUserResponse;
import com.github.galpiii.galpi.domain.user.entity.User;
import com.github.galpiii.galpi.domain.user.repository.UserRepository;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@RequiredArgsConstructor
class GithubUserWriter {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    User updateExistingOrCreate(GithubUserResponse githubUser) {
        return findAndSync(githubUser)
                .orElseGet(() -> userRepository.saveAndFlush(User.ofGithub(
                        githubUser.id(),
                        githubUser.login(),
                        githubUser.name(),
                        githubUser.email(),
                        githubUser.avatarUrl())));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<User> updateExisting(GithubUserResponse githubUser) {
        return findAndSync(githubUser);
    }

    /**
     * 연결 해제는 GitHub 호출을 트랜잭션 밖에서 끝낸 뒤 상태만 바꾼다.
     * 호출부가 트랜잭션을 열지 않는 이유는 {@code GithubConnectionService}를 참고.
     */
    @Transactional
    void disconnectGithub(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED))
                .disconnectGithub();
    }

    private Optional<User> findAndSync(GithubUserResponse githubUser) {
        return userRepository.findByGithubId(githubUser.id())
                .map(user -> {
                    user.syncGithubProfile(
                            githubUser.login(),
                            githubUser.name(),
                            githubUser.email(),
                            githubUser.avatarUrl());
                    return user;
                });
    }
}
